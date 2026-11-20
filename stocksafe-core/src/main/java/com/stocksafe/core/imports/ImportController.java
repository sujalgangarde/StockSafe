package com.stocksafe.core.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Upload the CSV as the raw request body (Content-Type: text/csv). Returns 202 with a job id. */
@RestController
@RequestMapping("/api/v1")
@PreAuthorize("hasAnyRole('OWNER','MANAGER')")
public class ImportController {
    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/shops/{shopId}/imports/products", consumes = "text/csv")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> importProducts(@PathVariable UUID shopId, @RequestBody String csv,
                                            @RequestParam(required = false) String fileName) {
        return Map.of("jobId", service.start(shopId, "PRODUCTS", csv, fileName));
    }

    @PostMapping(value = "/shops/{shopId}/imports/stock", consumes = "text/csv")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> importStock(@PathVariable UUID shopId, @RequestBody String csv,
                                         @RequestParam(required = false) String fileName) {
        return Map.of("jobId", service.start(shopId, "STOCK", csv, fileName));
    }

    @GetMapping("/imports/{id}")
    public Map<String, Object> job(@PathVariable UUID id) {
        return service.job(id);
    }

    @GetMapping("/imports/{id}/errors")
    public List<Map<String, Object>> errors(@PathVariable UUID id,
                                            @RequestParam(defaultValue = "1000") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return service.errors(id, limit, offset);
    }

    @GetMapping(value = "/imports/{id}/errors.csv", produces = "text/csv")
    public ResponseEntity<String> errorsCsv(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-errors.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(service.errorsCsv(id));
    }

    @PostMapping("/imports/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resume(@PathVariable UUID id) {
        service.resume(id);
    }
}
