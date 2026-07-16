package project.demotradingapp.dto.common;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PaginationResponse<T>{
    private List<T> contents;
    private int page;
    private int size;
    private Long totalElements;
    private int totalPages;
}
