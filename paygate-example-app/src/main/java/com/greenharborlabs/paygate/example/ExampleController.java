package com.greenharborlabs.paygate.example;

import com.greenharborlabs.paygate.spring.PaymentRequired;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ExampleController {

  record HealthResponse(String status) {}

  record DataResponse(String data, String timestamp) {}

  record QuoteResponse(String quote, String author, String timestamp) {}

  record AnalyzeRequest(String content) {}

  record AnalyzeResponse(String result, int wordCount, String timestamp) {}

  @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
  public HealthResponse health() {
    return new HealthResponse("ok");
  }

  @PaymentRequired(priceSats = 5, description = "Premium quote of the day")
  @GetMapping(value = "/quote", produces = MediaType.APPLICATION_JSON_VALUE)
  public QuoteResponse quote() {
    return new QuoteResponse(
        "The Times 03/Jan/2009 Chancellor on brink of second bailout for banks.",
        "Satoshi Nakamoto",
        Instant.now().toString());
  }

  @PaymentRequired(priceSats = 10, timeoutSeconds = 3600)
  @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
  public DataResponse data() {
    return new DataResponse("premium content", Instant.now().toString());
  }

  @PaymentRequired(priceSats = 50, timeoutSeconds = 3600, pricingStrategy = "analysisPricer")
  @PostMapping(
      value = "/analyze",
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public AnalyzeResponse analyze(@RequestBody AnalyzeRequest request) {
    String content = request.content() != null ? request.content() : "";
    int wordCount = content.isBlank() ? 0 : content.trim().split("\\s+").length;
    return new AnalyzeResponse("Analysis complete", wordCount, Instant.now().toString());
  }
}
