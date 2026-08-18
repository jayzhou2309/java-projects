package project.recommendationsalgo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import project.recommendationsalgo.dto.content.ContentResponse;
import project.recommendationsalgo.dto.content.CreateContentRequest;
import project.recommendationsalgo.entities.Content;
import project.recommendationsalgo.entities.User;
import project.recommendationsalgo.mapper.ContentMapper;
import project.recommendationsalgo.repository.ContentRepository;

@Service
@RequiredArgsConstructor
public class ContentService {
    private final ContentRepository contentRepository;
    private final ContentMapper contentMapper;

    // Create — creator resolved from authenticated principal, from Controller;
    public ContentResponse create(CreateContentRequest request, User creator){
        Content content = Content.builder()
                .title(request.getTitle())
                .category(request.getCategory())
                .metadata(request.getMetadata())
                .creator(creator)
                .build();
        contentRepository.save(content);
        return contentMapper.toContentResponse(content);
    }

    // Single lookup — throws if not found (backs GET /content/{id})
    public ContentResponse getById(Long id){
        Content content = contentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Content ID does not exist"));
        return contentMapper.toContentResponse(content);
    }

    // Paginated list (backs GET /content) — enforce a max page size, never unbounded
    public Page<ContentResponse> list(Pageable pageable){
        Pageable safePageable = capPageSize(pageable, 50);
        return contentRepository.findAll(safePageable)
                .map(ContentMapper::toContentResponse);
    }

    // Helper for max page size;
    private Pageable capPageSize(Pageable pageable, int maxSize){
        if (pageable.getPageSize() > maxSize){
            return PageRequest.of(pageable.getPageNumber(), maxSize, pageable.getSort());
        }
        return pageable;
    }

    // Paginated + filtered by category — likely needed for "user's top interacted categories" scoring later
    public Page<ContentResponse> listByCategory(String category, Pageable pageable){
        Pageable categoryPage = capPageSize(pageable, 50);
        return contentRepository.findByCategory(category, categoryPage)
                .map(ContentMapper::toContentResponse);
    }

    // Paginated list by creator — useful for a "my content" view, and for RecommendationService candidate generation
    public Page<ContentResponse> listByCreator(User creator, Pageable pageable){
        Pageable creatorPage = capPageSize(pageable, 50);
        return contentRepository.findByCreator(creator, creatorPage)
                .map(ContentMapper::toContentResponse);
    }

    // Existence check — cheap validation before creating an Interaction referencing a contentId
    public boolean existsById(Long id){
        return contentRepository.existsById(id);
    }
}
