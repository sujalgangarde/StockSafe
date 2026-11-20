package com.stocksafe.core.imports;

import com.stocksafe.core.audit.AuditService;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.product.ProductService;
import com.stocksafe.core.shop.ShopAccess;
import com.stocksafe.core.sse.SseHub;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.stocksafe.core.stock.StockService;
import com.stocksafe.core.stock.StockService.ReceiveRequest;
import com.stocksafe.core.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CSV import in chunks of 500 rows.
 * - Bad rows are recorded in import_errors; good rows are saved (partial failure is fine).
 * - Each chunk commits together with its checkpoint (processed_rows). If the server dies,
 *   the next resume continues from the checkpoint. No row is done twice.
 */
@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    static final int CHUNK = 500;
    private static final Set<String> PRODUCT_COLUMNS =
            Set.of("barcode", "name", "unit", "gst_rate", "reorder_level");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ProductService products;
    private final StockService stock;
    private final ShopAccess shops;
    private final AuditService audit;
    private final Executor executor;
    private final SseHub sse;
    private final MeterRegistry metrics;

    public ImportService(JdbcTemplate jdbc, TransactionTemplate tx, ProductService products, StockService stock,
                         ShopAccess shops, AuditService audit, SseHub sse, MeterRegistry metrics,
                         @Qualifier("importExecutor") Executor executor) {
        this.sse = sse;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.tx = tx;
        this.products = products;
        this.stock = stock;
        this.shops = shops;
        this.audit = audit;
        this.executor = executor;
    }

    // ---------- API ----------

    public UUID start(UUID shopId, String kind, String csv, String fileName) {
        shops.require(shopId);
        if (csv == null || csv.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV is empty");
        int total = dataLines(csv).size();
        if (total == 0) throw new ApiException(HttpStatus.BAD_REQUEST, "CSV has a header but no rows");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into import_jobs(id, tenant_id, shop_id, kind, status, file_name, payload, total_rows) "
                        + "values (?,?,?,?, 'RUNNING', ?, ?, ?)",
                id, TenantContext.require(), shopId, kind, fileName, csv, total);
        audit.log("IMPORT_STARTED", "import_job", id, Map.of("kind", kind, "rows", total));
        submit(id);
        return id;
    }

    /** Continue a FAILED job, or a RUNNING job that has not moved for 2 minutes (server died). */
    public void resume(UUID jobId) {
        int claimed = jdbc.update("""
                update import_jobs set status = 'RUNNING', message = null, updated_at = now()
                where id = ? and (status in ('FAILED','QUEUED')
                   or (status = 'RUNNING' and updated_at < now() - interval '2 minutes'))""", jobId);
        if (claimed == 0) {
            Integer exists = jdbc.queryForObject("select count(*) from import_jobs where id = ?", Integer.class, jobId);
            if (exists == null || exists == 0) throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
            throw new ApiException(HttpStatus.CONFLICT, "Job is done or still running");
        }
        submit(jobId);
    }

    public Map<String, Object> job(UUID jobId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, shop_id, kind, status, file_name, total_rows, processed_rows, success_rows, error_rows,
                       message, created_at, updated_at from import_jobs where id = ?""", jobId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        return rows.get(0);
    }

    public List<Map<String, Object>> errors(UUID jobId, int limit, int offset) {
        job(jobId);
        return jdbc.queryForList("select row_no, message, raw from import_errors where job_id = ? "
                + "order by row_no limit ? offset ?", jobId, Math.min(Math.max(limit, 1), 5000), Math.max(offset, 0));
    }

    public String errorsCsv(UUID jobId) {
        job(jobId);
        StringBuilder sb = new StringBuilder("row_no,message,raw\n");
        for (Map<String, Object> e : jdbc.queryForList(
                "select row_no, message, raw from import_errors where job_id = ? order by row_no", jobId)) {
            sb.append(e.get("row_no")).append(',').append(csv(e.get("message"))).append(',')
              .append(csv(e.get("raw"))).append('\n');
        }
        return sb.toString();
    }

    // ---------- background work ----------

    private void submit(UUID jobId) {
        UUID tenant = TenantContext.require();
        executor.execute(() -> TenantContext.callAs(tenant, () -> {
            runJob(jobId);
            return null;
        }));
    }

    private void runJob(UUID jobId) {
        Timer.Sample timer = Timer.start(metrics);
        try {
            Map<String, Object> job = jdbc.queryForMap(
                    "select shop_id, kind, payload, processed_rows from import_jobs where id = ?", jobId);
            UUID shopId = (UUID) job.get("shop_id");
            String kind = (String) job.get("kind");
            String payload = (String) job.get("payload");
            int done = ((Number) job.get("processed_rows")).intValue();

            List<String> header = parseLine(payload.split("\r?\n", 2)[0]).stream().map(String::toLowerCase).toList();
            List<String> rows = dataLines(payload);
            boolean expiryRequired = shops.expiryRequired(shopId);

            while (done < rows.size()) {
                final int from = done;
                final int to = Math.min(done + CHUNK, rows.size());
                tx.executeWithoutResult(status -> {
                    int ok = 0;
                    List<Object[]> errs = new ArrayList<>();
                    for (int i = from; i < to; i++) {
                        try {
                            handleRow(kind, shopId, header, parseLine(rows.get(i)), expiryRequired);
                            ok++;
                        } catch (ApiException e) {
                            errs.add(new Object[]{UUID.randomUUID(), TenantContext.require(), jobId, i + 1,
                                    e.getMessage(), truncate(rows.get(i))});
                        }
                    }
                    if (!errs.isEmpty()) {
                        jdbc.batchUpdate("insert into import_errors(id, tenant_id, job_id, row_no, message, raw) "
                                + "values (?,?,?,?,?,?)", errs);
                    }
                    // Checkpoint in the SAME transaction as the rows it covers.
                    jdbc.update("update import_jobs set processed_rows = ?, success_rows = success_rows + ?, "
                            + "error_rows = error_rows + ?, updated_at = now() where id = ?",
                            to, ok, errs.size(), jobId);
                });
                done = to;
                sse.publish(shopId, "import.progress", Map.of("jobId", jobId.toString(), "processed", done,
                        "total", rows.size(), "kind", kind));
            }
            jdbc.update("update import_jobs set status = 'DONE', updated_at = now() where id = ?", jobId);
            timer.stop(Timer.builder("stocksafe.import.duration").tag("kind", kind).register(metrics));
        } catch (Exception e) {
            log.error("Import {} failed", jobId, e);
            jdbc.update("update import_jobs set status = 'FAILED', message = ?, updated_at = now() where id = ?",
                    truncate(String.valueOf(e.getMessage())), jobId);
        }
    }

    // ---------- row handlers (validate first, then write) ----------

    private void handleRow(String kind, UUID shopId, List<String> h, List<String> r, boolean expiryRequired) {
        if (kind.equals("PRODUCTS")) productRow(shopId, h, r); else stockRow(shopId, h, r, expiryRequired);
    }

    private void productRow(UUID shopId, List<String> h, List<String> r) {
        String name = col(h, r, "name");
        if (name.isBlank()) throw bad("name is required");
        BigDecimal gst = decimal(h, r, "gst_rate", BigDecimal.ZERO);
        if (gst.signum() < 0 || gst.compareTo(BigDecimal.valueOf(28)) > 0) throw bad("gst_rate must be 0 to 28");
        int reorder = integer(h, r, "reorder_level", 0);
        if (reorder < 0) throw bad("reorder_level cannot be negative");
        Map<String, Object> attrs = new HashMap<>();
        for (int i = 0; i < h.size() && i < r.size(); i++) {      // extra columns become custom fields
            if (!PRODUCT_COLUMNS.contains(h.get(i)) && !r.get(i).isBlank()) attrs.put(h.get(i), r.get(i));
        }
        products.createInternal(shopId, name, col(h, r, "barcode"), col(h, r, "unit"), gst, reorder, attrs);
    }

    private void stockRow(UUID shopId, List<String> h, List<String> r, boolean expiryRequired) {
        String barcode = col(h, r, "barcode");
        if (barcode.isBlank()) throw bad("barcode is required");
        String batchNo = col(h, r, "batch_no");
        if (batchNo.isBlank()) throw bad("batch_no is required");
        LocalDate expiry = null;
        String e = col(h, r, "expiry_date");
        if (!e.isBlank()) {
            try {
                expiry = LocalDate.parse(e);
            } catch (DateTimeParseException ex) {
                throw bad("expiry_date must look like 2027-03-31");
            }
        }
        BigDecimal cost = decimal(h, r, "cost", null);
        BigDecimal mrp = decimal(h, r, "mrp", null);
        if (cost == null || mrp == null || cost.signum() < 0 || mrp.signum() < 0) throw bad("cost and mrp are required");
        int qty = integer(h, r, "qty", 0);
        if (qty < 1) throw bad("qty must be 1 or more");
        List<UUID> pid = jdbc.queryForList(
                "select id from products where shop_id = ? and barcode = ? and deleted_at is null",
                UUID.class, shopId, barcode);
        if (pid.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Unknown barcode: " + barcode);
        stock.doReceive(shopId, expiryRequired, new ReceiveRequest(pid.get(0), batchNo, expiry, cost, mrp, qty, null), null);
    }

    // ---------- small CSV helpers ----------

    /** Non-blank lines after the header. Row number n = index n-1 in this list. */
    static List<String> dataLines(String csv) {
        String[] lines = csv.split("\r?\n");
        List<String> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) out.add(lines[i]);
        return out;
    }

    /** Comma split with "quoted, values" and "" as an escaped quote. One line only. */
    static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else quoted = false;
                } else sb.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { out.add(sb.toString().trim()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString().trim());
        return out;
    }

    private static String col(List<String> h, List<String> r, String name) {
        int i = h.indexOf(name);
        return (i < 0 || i >= r.size()) ? "" : r.get(i);
    }

    private static BigDecimal decimal(List<String> h, List<String> r, String name, BigDecimal def) {
        String v = col(h, r, name);
        if (v.isBlank()) return def;
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw bad(name + " must be a number");
        }
    }

    private static int integer(List<String> h, List<String> r, String name, int def) {
        String v = col(h, r, name);
        if (v.isBlank()) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw bad(name + " must be a whole number");
        }
    }

    private static ApiException bad(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, msg);
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s);
    }

    private static String csv(Object o) {
        String s = o == null ? "" : o.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
