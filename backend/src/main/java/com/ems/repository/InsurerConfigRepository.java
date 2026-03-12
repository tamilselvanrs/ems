package com.ems.repository;

import com.ems.domain.model.InsurerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InsurerConfigRepository extends JpaRepository<InsurerConfig, UUID> {

    Optional<InsurerConfig> findByInsurerId(UUID insurerId);
}
