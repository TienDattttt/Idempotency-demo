package com.example.payment.client;

public class MaxRetryExceededException extends Exception {
    public MaxRetryExceededException(String message) {
        super(message);
    }
}
