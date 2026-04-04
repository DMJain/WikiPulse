package com.wikipulse.worker.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEditRepository extends JpaRepository<ProcessedEdit, Long> {}
