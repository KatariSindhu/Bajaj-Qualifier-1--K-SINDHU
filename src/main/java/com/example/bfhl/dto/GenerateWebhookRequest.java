package com.example.bfhl.dto;


import lombok.AllArgsConstructor;
import lombok.Data;


@Data
@AllArgsConstructor
public class GenerateWebhookRequest {
private String name;
private String regNo;
private String email;
}