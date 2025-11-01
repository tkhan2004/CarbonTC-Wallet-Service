package com.carbontc.walletservice.consumer;

import com.carbontc.walletservice.config.RabbitMQConfig;
import com.carbontc.walletservice.dto.event.CreditIssuedEvent;
import com.carbontc.walletservice.dto.event.TransactionCompletedEvent;
import com.carbontc.walletservice.dto.event.TransactionCreatedEvent;
import com.carbontc.walletservice.dto.request.CreditTransferRequestForConsumer;
import com.carbontc.walletservice.entity.Certificate;
import com.carbontc.walletservice.entity.EWallet;
import com.carbontc.walletservice.entity.status.FeeType;
import com.carbontc.walletservice.entity.status.TransferType;
import com.carbontc.walletservice.exception.BusinessException;
import com.carbontc.walletservice.service.CarbonWalletsService;
import com.carbontc.walletservice.service.CertificateService;
import com.carbontc.walletservice.service.EWalletService;
import com.carbontc.walletservice.service.TransactionFeeService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Đảm bảo import đúng

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class RabbitMQConsumerService {

    private final EWalletService eWalletService;
    private final CarbonWalletsService carbonWalletsService;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionFeeService transactionFeeService;
    private final CertificateService certificateService;


    private static final Logger log = LoggerFactory.getLogger(RabbitMQConsumerService.class);


    /**
     * Lắng nghe sự kiện MUA BÁN từ Marketplace Service.
     */
    @RabbitListener(queues = RabbitMQConfig.TRANSACTION_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleTransaction(TransactionCreatedEvent event) {
        log.info(">>>>>> RAW EVENT RECEIVED: {}", event);
        if (event != null) {
            log.info(">>>>>> Buyer ID from Event: {}", event.getBuyerUserId());
            log.info(">>>>>> Seller ID from Event: {}", event.getSellerUserId());
            log.info(">>>>>> Transaction ID from Event: {}", event.getTransactionId());
        } else {
            log.error(">>>>>> EVENT RECEIVED IS NULL! Skipping processing.");
            return;
        }

        log.info("Nhận được TransactionCreatedEvent: {}", event.getTransactionId());

        try {
            // 1. Tìm ví
            EWallet buyerEwallet = eWalletService.findWalletByUserId(event.getBuyerUserId());
            EWallet sellerEwallet = eWalletService.findWalletByUserId(event.getSellerUserId());

            // 2. Lấy phí từ event (Logic này đúng nếu Marketplace tính phí)
            BigDecimal platformFee = event.getPlatformFee();
            BigDecimal amountSellerReceives = event.getMoneyAmount().subtract(platformFee);

            log.info("Giao dịch {}: Tổng tiền {}, Phí {}, Người bán nhận {}",
                    event.getTransactionId(), event.getMoneyAmount(), platformFee, amountSellerReceives);

            // 3. Trừ tiền người mua
            eWalletService.withdraw(buyerEwallet.getWalletId(), event.getMoneyAmount());

            // 4. Cộng tiền cho người bán (đã trừ phí)
            eWalletService.deposit(sellerEwallet.getWalletId(), amountSellerReceives);

            // 5. Chuyển tín chỉ
            CreditTransferRequestForConsumer transferRequest = new CreditTransferRequestForConsumer();
            transferRequest.setToUserId(event.getBuyerUserId());
            transferRequest.setAmount(event.getCreditAmount());
            transferRequest.setTransferType(TransferType.SALE);
            transferRequest.setReferenceId(event.getTransactionId());
            carbonWalletsService.transferCredits(event.getSellerUserId(), transferRequest);

            // 6. Ghi nhận phí giao dịch
            transactionFeeService.recordFee(event.getTransactionId(), platformFee, FeeType.PLATFORM_FEE);

            // 7. Tạo chứng nhận
            Certificate newCert = certificateService.createCertificate(event.getTransactionId(), event.getBuyerUserId(), event.getCreditAmount());

            // 8. Gửi tin nhắn COMPLETED (Đã sửa đích và key)
            TransactionCompletedEvent completedEvent = TransactionCompletedEvent.builder()
                    .transactionId(event.getTransactionId())
                    .status("COMPLETED")
                    .certificateId(newCert.getCertificateId())
                    .completedAt(LocalDateTime.now())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRANSACTION_EXCHANGE, "transaction.completed", completedEvent);

            log.info("Xử lý TransactionId: {} thành công", event.getTransactionId());

        } catch (BusinessException e) {
            log.error("Xử lý TransactionId: {} thất bại (Lỗi nghiệp vụ): {}", event.getTransactionId(), e.getMessage());

            // Gửi tin nhắn FAILED
            TransactionCompletedEvent failed = TransactionCompletedEvent.builder()
                    .transactionId(event.getTransactionId())
                    .status("FAILED")
                    .message(e.getMessage())
                    .completedAt(LocalDateTime.now())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRANSACTION_EXCHANGE, "transaction.failed", failed);

        } catch (Exception e) {
            log.error("Xử lý TransactionId: {} thất bại (Lỗi hệ thống): {}", event.getTransactionId(), e.getMessage(), e); // Log cả stack trace


            TransactionCompletedEvent failed = TransactionCompletedEvent.builder()
                    .transactionId(event.getTransactionId())
                    .status("FAILED")
                    .message("Lỗi hệ thống: " + e.getMessage())
                    .completedAt(LocalDateTime.now())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRANSACTION_EXCHANGE, "transaction.failed", failed);

        }
    }

    /**
     * Lắng nghe sự kiện PHÁT HÀNH TÍN CHỈ MỚI
     */
    @RabbitListener(queues = RabbitMQConfig.CREDIT_QUEUE)
    @Transactional(rollbackFor = Exception.class)
    public void handleCreditIssued(CreditIssuedEvent creditIssuedEvent) {
        log.info("Nhận được CreditIssuedEvent cho user: {}", creditIssuedEvent.getOwnerUserId());

        try {
            carbonWalletsService.issueNewCredits(creditIssuedEvent);
            log.info("Cộng {} tín chỉ cho user {} thành công!",
                    creditIssuedEvent.getCreditAmount(), creditIssuedEvent.getOwnerUserId());

        } catch (BusinessException e) { // Bắt lỗi nghiệp vụ
            log.error("Cộng tín chỉ cho user {} thất bại (Lỗi nghiệp vụ): {}", creditIssuedEvent.getOwnerUserId(), e.getMessage());
        } catch (Exception e) { // Bắt lỗi hệ thống
            log.error("Cộng tín chỉ cho user {} thất bại (Lỗi hệ thống): {}", creditIssuedEvent.getOwnerUserId(), e.getMessage(), e);
        }
    }
}