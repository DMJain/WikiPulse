package com.wikipulse.worker.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEditRepository extends JpaRepository<ProcessedEdit, Long> {

  List<ProcessedEdit> findByOrderByEditTimestampDesc(Pageable pageable);
}
