package project.ragdemo.sec.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class Recent {
    private List<String> form;
    private List<String> filingDate;
    private List<String> accessionNumber;
    private List<String> primaryDocument;
}
