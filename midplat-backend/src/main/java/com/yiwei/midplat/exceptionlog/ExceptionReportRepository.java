package com.yiwei.midplat.exceptionlog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ExceptionReportRepository extends JpaRepository<ExceptionReport, String>, JpaSpecificationExecutor<ExceptionReport> {
}
