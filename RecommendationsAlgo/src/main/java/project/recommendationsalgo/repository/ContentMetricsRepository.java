package project.recommendationsalgo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.recommendationsalgo.entities.ContentMetrics;

@Repository
public interface ContentMetricsRepository extends JpaRepository<ContentMetrics, Long> {
}
