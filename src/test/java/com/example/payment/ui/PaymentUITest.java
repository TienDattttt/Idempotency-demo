package com.example.payment.ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bonigarcia.wdm.WebDriverManager;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("h2")
@EnabledIfSystemProperty(named = "runUiTests", matches = "true")
class PaymentUITest {

    @Value("${server.port:8080}")
    private int serverPort;

    private WebDriver driver;

    @BeforeEach
    void setUp() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--disable-dev-shm-usage");
        driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    @Test
    void submitForm_withValidAmount_redirectsToResultAndShowsSuccessMessage() {
        driver.get(baseUrl() + "/payment");
        driver.findElement(By.id("amount")).sendKeys("150000");
        driver.findElement(By.id("btnSubmit")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.urlContains("/payment/result"));

        WebElement resultMessage = new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.visibilityOfElementLocated(By.id("resultMessage")));
        assertThat(resultMessage.getText()).containsIgnoringCase("thanh");
    }

    @Test
    void submitForm_withMinimumValidAmount_redirectsToResultAndShowsSuccessMessage() {
        driver.get(baseUrl() + "/payment");
        WebElement amount = driver.findElement(By.id("amount"));
        amount.clear();
        amount.sendKeys("1");
        driver.findElement(By.id("btnSubmit")).click();

        new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.urlContains("/payment/result"));

        WebElement resultMessage = new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.visibilityOfElementLocated(By.id("resultMessage")));
        assertThat(resultMessage.getText()).containsIgnoringCase("thanh");
    }

    @Disabled("BUG-004: Open - error message not specific enough")
    @Test
    void submitForm_whenServerReturnsError_showsSpecificErrorMessage() {
        driver.get(baseUrl() + "/payment/result?success=false&error=Internal%20Server%20Error");

        WebElement errorMessage = new WebDriverWait(driver, Duration.ofSeconds(10))
            .until(ExpectedConditions.visibilityOfElementLocated(By.id("errorMessage")));
        assertThat(errorMessage.getText()).isNotBlank();
        assertThat(errorMessage.getText()).isNotEqualTo("Có lỗi xảy ra");
    }

    private String baseUrl() {
        return "http://localhost:" + serverPort;
    }
}
