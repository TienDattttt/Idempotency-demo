package com.example.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentRequest {

    @NotNull(message = "Số tiền phải lớn hơn 0")
    @Min(value = 1, message = "Số tiền phải lớn hơn 0")
    private Long amount;

    @Size(max = 500, message = "Mô tả không được vượt quá 500 ký tự")
    private String description;
}
