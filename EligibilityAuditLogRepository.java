package University.exam.repository;

import University.exam.Entity.EligibilityAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EligibilityAuditLogRepository extends JpaRepository<EligibilityAuditLog, Long> {

    List<EligibilityAuditLog> findByExamIdOrderByUploadDateDesc(Long examId);

    List<EligibilityAuditLog> findAllByOrderByUploadDateDesc();
}
