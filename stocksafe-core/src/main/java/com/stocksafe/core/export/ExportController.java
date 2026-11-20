package com.stocksafe.core.export;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.stocksafe.core.common.ApiException;
import com.stocksafe.core.stock.StockService;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** GET /api/v1/shops/{shop}/reports/{near-expiry|low-stock|dead-stock}/export?format=csv|pdf */
@RestController
public class ExportController {
    private final StockService stock;

    public ExportController(StockService stock) {
        this.stock = stock;
    }

    @GetMapping("/api/v1/shops/{shopId}/reports/{type}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID shopId, @PathVariable String type,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(defaultValue = "30") int days) {
        List<Map<String, Object>> rows = switch (type) {
            case "near-expiry" -> stock.nearExpiry(shopId, days);
            case "low-stock" -> stock.lowStock(shopId);
            case "dead-stock" -> stock.deadStock(shopId, Math.max(days, 1));
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "Unknown report");
        };
        byte[] body;
        MediaType mt;
        switch (format) {
            case "csv" -> { body = csv(rows).getBytes(StandardCharsets.UTF_8); mt = MediaType.parseMediaType("text/csv"); }
            case "pdf" -> { body = pdf(type, rows); mt = MediaType.APPLICATION_PDF; }
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "format must be csv or pdf");
        }
        return ResponseEntity.ok().contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + type + "." + format + "\"")
                .body(body);
    }

    private static String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "\n";
        StringBuilder sb = new StringBuilder(String.join(",", rows.get(0).keySet())).append('\n');
        for (Map<String, Object> r : rows) {
            boolean first = true;
            for (Object v : r.values()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(v == null ? "" : v.toString().replace("\"", "\"\"")).append('"');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static byte[] pdf(String title, List<Map<String, Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("StockSafe report: " + title, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16)));
        doc.add(new Paragraph(" "));
        if (rows.isEmpty()) {
            doc.add(new Paragraph("Nothing to show."));
        } else {
            List<String> cols = rows.get(0).keySet().stream().filter(c -> !c.endsWith("_id")).toList();
            PdfPTable t = new PdfPTable(cols.size());
            t.setWidthPercentage(100);
            Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            for (String c : cols) {
                PdfPCell h = new PdfPCell(new Phrase(c, bold));
                h.setHorizontalAlignment(Element.ALIGN_LEFT);
                t.addCell(h);
            }
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);
            for (Map<String, Object> r : rows) {
                for (String c : cols) t.addCell(new Phrase(String.valueOf(r.get(c) == null ? "" : r.get(c)), normal));
            }
            doc.add(t);
        }
        doc.close();
        return out.toByteArray();
    }
}
