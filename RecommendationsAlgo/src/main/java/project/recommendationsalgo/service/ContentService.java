package project.recommendationsalgo.service;

import lombok.RequiredArgsConstructor;
import org.hibernate.query.Page;
import org.springframework.stereotype.Service;
import project.recommendationsalgo.entities.Content;
import project.recommendationsalgo.entities.User;
import project.recommendationsalgo.repository.ContentRepository;

import java.awt.print.Pageable;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentService {
    private final ContentRepository contentRepository;
    // Create — creator resolved from authenticated principal, never trust client-supplied creator id
    public Content create(String title, String category, Map<String, Object> metadata, User creator){

    }

    // Single lookup — throws if not found (backs GET /content/{id})
    public Content getById(Long id){
        return contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content ID does not exist"));
    }

    // Paginated list (backs GET /content) — enforce a max page size, never unbounded
    public Page<Content> list(Pageable pageable);

    // Paginated + filtered by category — likely needed for "user's top interacted categories" scoring later
    public Page<Content> listByCategory(String category, Pageable pageable);

    // Paginated list by creator — useful for a "my content" view, and for RecommendationService candidate generation
    public Page<Content> listByCreator(User creator, Pageable pageable);

    // Existence check — cheap validation before creating an Interaction referencing a contentId
    public boolean existsById(Long id){
        return contentRepository.existsById(id);
    }
}
