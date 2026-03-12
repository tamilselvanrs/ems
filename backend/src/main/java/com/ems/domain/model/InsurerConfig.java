package com.ems.domain.model;

import com.ems.domain.enums.ExecutionMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "insurer_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InsurerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "insurer_config_id")
    private UUID insurerConfigId;

    @Column(name = "insurer_id", nullable = false, unique = true)
    private UUID insurerId;

    @Column(name = "insurer_name", nullable = false, length = 200)
    private String insurerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 20)
    @Builder.Default
    private ExecutionMode executionMode = ExecutionMode.REALTIME;

    @Column(name = "backdate_window_days", nullable = false)
    @Builder.Default
    private Integer backdateWindowDays = 30;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
