package com.wikipulse.worker.repository;

import com.wikipulse.worker.entity.ProcessedEdit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEditRepository extends JpaRepository<ProcessedEdit, Long> {}
