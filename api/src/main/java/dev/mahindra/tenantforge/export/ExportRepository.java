package dev.mahindra.tenantforge.export;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExportRepository extends JpaRepository<ExportJob, UUID> {

    List<ExportJob> findTop20ByOrderByCreatedAtDesc();
}
