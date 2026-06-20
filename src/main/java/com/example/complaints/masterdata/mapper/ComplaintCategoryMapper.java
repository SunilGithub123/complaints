package com.example.complaints.masterdata.mapper;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.masterdata.dto.ComplaintCategoryResponse;
import com.example.complaints.masterdata.model.ComplaintCategory;
import org.springframework.stereotype.Component;

@Component
public class ComplaintCategoryMapper {

    public ComplaintCategoryResponse toResponse(ComplaintCategory c) {
        return new ComplaintCategoryResponse(
                c.getId(),
                c.getCode(),
                c.getName(),
                c.getSlaHours(),
                c.isActive(),
                DateUtils.toIst(c.getCreatedAt()),
                DateUtils.toIst(c.getUpdatedAt())
        );
    }
}

