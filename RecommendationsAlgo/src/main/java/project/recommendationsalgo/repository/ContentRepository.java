package project.recommendationsalgo.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.recommendationsalgo.entities.Content;
import project.recommendationsalgo.entities.User;

import java.util.Optional;

@Repository
public interface ContentRepository extends JpaRepository<Content, Long> {
    Page<Content> findByCategory(String category, Pageable pageable);

    Page<Content> findByCreator(User creator, Pageable creatorPage);
}
