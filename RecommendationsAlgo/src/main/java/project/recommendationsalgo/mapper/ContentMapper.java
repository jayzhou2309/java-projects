package project.recommendationsalgo.mapper;


import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import project.recommendationsalgo.dto.content.ContentResponse;
import project.recommendationsalgo.entities.Content;

@Component
@RequiredArgsConstructor
public class ContentMapper {
    public static ContentResponse toContentResponse(Content content){
        return ContentResponse.builder()
                .id(content.getId())
                .title(content.getTitle())
                .category(content.getCategory())
                .metadata(content.getMetadata())
                .creator(content.getCreator())
                .createdAt(content.getCreatedAt())
                .build();
    }
}
