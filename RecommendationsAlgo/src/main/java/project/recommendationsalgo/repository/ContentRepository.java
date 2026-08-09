package project.recommendationsalgo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.recommendationsalgo.entities.Content;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {
}
