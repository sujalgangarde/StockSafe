package com.stocksafe.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SuppressWarnings({"rawtypes", "unchecked"})
class ImportTest extends AbstractIntegrationTest {

    private String startImport(String token, String shop, String kind, String csv) {
        HttpHeaders h = auth(token);
        h.setContentType(MediaType.parseMediaType("text/csv"));
        ResponseEntity<Map> r = rest.exchange("/api/v1/shops/" + shop + "/imports/" + kind, HttpMethod.POST,
                new HttpEntity<>(csv, h), Map.class);
        assertThat(r.getStatusCode().value()).isEqualTo(202);
        return r.getBody().get("jobId").toString();
    }

    private Map waitDone(String token, String job) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(300)).until(() -> {
            Map m = rest.exchange("/api/v1/imports/" + job, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();
            return "DONE".equals(m.get("status")) || "FAILED".equals(m.get("status"));
        });
        return rest.exchange("/api/v1/imports/" + job, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();
    }

    @Test
    void product_import_reports_every_bad_row_and_respects_plan_limit() {
        Map a = signup("Import Store");
        String t = token(a);
        String shop = createShopRaw(t, "Main").getBody().get("id").toString();

        StringBuilder csv = new StringBuilder("barcode,name,unit,gst_rate,reorder_level,manufacturer\n");
        for (int i = 1; i <= 205; i++) {
            String barcode = (i == 3) ? "BC2" : "BC" + i;              // row 3 repeats row 2's barcode
            String gst = (i == 4) ? "99" : "12";                       // row 4 has a bad GST rate
            csv.append(barcode).append(",\"Item, ").append(i).append("\",pcs,").append(gst).append(",5,Acme\n");
        }
        String job = startImport(t, shop, "products", csv.toString());
        Map done = waitDone(t, job);

        // Free plan = 200 products. 205 rows - duplicate - bad gst = 203 valid; last 3 hit the limit.
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("total_rows")).isEqualTo(205);
        assertThat(done.get("processed_rows")).isEqualTo(205);
        assertThat(done.get("success_rows")).isEqualTo(200);
        assertThat(done.get("error_rows")).isEqualTo(5);

        List errors = rest.exchange("/api/v1/imports/" + job + "/errors", HttpMethod.GET,
                new HttpEntity<>(auth(t)), List.class).getBody();
        assertThat(errors).hasSize(5);
        String report = rest.exchange("/api/v1/imports/" + job + "/errors.csv", HttpMethod.GET,
                new HttpEntity<>(auth(t)), String.class).getBody();
        assertThat(report).startsWith("row_no,message,raw");

        Map me = rest.exchange("/api/v1/me", HttpMethod.GET, new HttpEntity<>(auth(t)), Map.class).getBody();
        assertThat(((Map) ((Map) me.get("usage")).get("products")).get("used")).isEqualTo(200);

        // A finished job cannot be resumed
        ResponseEntity<Map> again = rest.exchange("/api/v1/imports/" + job + "/resume", HttpMethod.POST,
                new HttpEntity<>(auth(t)), Map.class);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void stock_import_checks_barcode_expiry_and_dates() {
        Map a = signup("Stock Import");
        String t = token(a);
        String shop = createShopRaw(t, "Main").getBody().get("id").toString();   // MEDICAL: expiry required

        startImportAndWait(t, shop, "products", "barcode,name\nS1,Alpha\nS2,Beta\n");

        String d = LocalDate.now().plusDays(120).toString();
        String stockCsv = "barcode,batch_no,expiry_date,cost,mrp,qty\n"
                + "S1,B1," + d + ",5,8,10\n"        // ok
                + "S2,B1," + d + ",5,8,10\n"        // ok
                + "S9,B1," + d + ",5,8,10\n"        // unknown barcode
                + "S1,B2,,5,8,10\n"                 // missing expiry (medical shop)
                + "S1,B3,31-12-2030,5,8,10\n";      // bad date format
        Map done = startImportAndWait(t, shop, "stock", stockCsv);
        assertThat(done.get("success_rows")).isEqualTo(2);
        assertThat(done.get("error_rows")).isEqualTo(3);
    }

    private Map startImportAndWait(String t, String shop, String kind, String csv) {
        return waitDone(t, startImport(t, shop, kind, csv));
    }
}
