package main.java.com.example.bfhl.service;

import com.example.bfhl.dto.GenerateWebhookRequest;
import com.example.bfhl.dto.GenerateWebhookResponse;
import com.example.bfhl.dto.SubmitSolutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
public class BfhlService {

    private final RestTemplate rest = new RestTemplate();

    @Value("${bfhl.generate-url}")
    private String generateUrl;

    @Value("${bfhl.fallback-submit-url}")
    private String fallbackSubmitUrl;

    @Value("${bfhl.auth-prefix:}") // empty by default, can be overridden with "Bearer "
    private String authPrefix;

    private final String outFile = "output.sql"; // file to store SQL locally

    public void runFlow(String name, String regNo, String email) {
        try {
            // 1) Generate webhook
            GenerateWebhookResponse gw = generateWebhook(name, regNo, email);
            String webhook = gw.getWebhook() != null ? gw.getWebhook() : fallbackSubmitUrl;
            log.info("Webhook: {}", webhook);

            // 2) Decide which SQL file to use
            boolean isOdd = isLastTwoDigitsOdd(regNo);
            String sqlPath = isOdd ? "sql/q1.sql" : "sql/q2.sql";
            String finalQuery = loadClasspath(sqlPath);
            if (finalQuery == null || finalQuery.isBlank()) {
                throw new IllegalStateException("Missing or empty SQL in resources/" + sqlPath);
            }
            log.info("Using {} based on regNo {} (odd? {})", sqlPath, regNo, isOdd);

            // 3) Store result locally
            writeToFile(outFile, finalQuery);
            log.info("Final SQL stored at: {}", outFile);

            // 4) Submit to webhook with JWT in Authorization header
            postSolution(webhook, gw.getAccessToken(), finalQuery);
            log.info("Submitted successfully ✅");

        } catch (Exception e) {
            log.error("Flow failed", e);
        }
    }

    private GenerateWebhookResponse generateWebhook(String name, String regNo, String email) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<GenerateWebhookRequest> entity =
                new HttpEntity<>(new GenerateWebhookRequest(name, regNo, email), headers);

        ResponseEntity<GenerateWebhookResponse> resp =
                rest.postForEntity(generateUrl, entity, GenerateWebhookResponse.class);

        return resp.getBody();
    }

    private void postSolution(String webhook, String accessToken, String finalQuery) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authPrefix + accessToken);

        HttpEntity<SubmitSolutionRequest> entity =
                new HttpEntity<>(new SubmitSolutionRequest(finalQuery), headers);

        try {
            ResponseEntity<Map> resp = rest.postForEntity(webhook, entity, Map.class);
            log.info("Webhook response: status={} body={}", resp.getStatusCode(), resp.getBody());
        } catch (HttpClientErrorException.Unauthorized e) {
            // retry with Bearer prefix if first attempt fails
            if (authPrefix == null || authPrefix.isBlank()) {
                log.warn("401 Unauthorized. Retrying with 'Bearer ' prefix...");
                headers.set("Authorization", "Bearer " + accessToken);
                ResponseEntity<Map> resp = rest.postForEntity(
                        webhook,
                        new HttpEntity<>(new SubmitSolutionRequest(finalQuery), headers),
                        Map.class
                );
                log.info("Retry response: status={} body={}", resp.getStatusCode(), resp.getBody());
            } else {
                throw e;
            }
        }
    }

    private static boolean isLastTwoDigitsOdd(String regNo) {
        String digits = regNo.replaceAll("\\D+", "");
        String last2 = digits.length() >= 2 ? digits.substring(digits.length() - 2) : digits;
        if (last2.isEmpty()) throw new IllegalArgumentException("regNo must end with digits");
        int val = Integer.parseInt(last2);
        return (val % 2) == 1;
    }

    private static String loadClasspath(String path) throws Exception {
        try (var in = BfhlService.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).trim();
        }
    }

    private static void writeToFile(String file, String content) throws Exception {
        java.nio.file.Files.writeString(java.nio.file.Path.of(file), content, StandardCharsets.UTF_8);
    }
}
