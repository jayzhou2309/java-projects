package project.paymentprocessing.entity;

import jakarta.persistence.*;
import lombok.*;
import project.paymentprocessing.model.Currency;
import project.paymentprocessing.model.Status;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;
    @Column(name = "sender_account", nullable = false)
    private String senderAccount;
    @Column(name = "receiver_account", nullable = false)
    private String receiverAccount;
    @Column(name = "amount", nullable = false)
    private BigDecimal amount;
    @Column(name = "currency", nullable = false)
    @Enumerated(EnumType.STRING)
    private Currency currency;
    @Column(name = "status" ,nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
