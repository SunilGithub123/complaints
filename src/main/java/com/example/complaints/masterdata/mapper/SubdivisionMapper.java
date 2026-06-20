package com.example.complaints.masterdata.mapper;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.masterdata.dto.SubdivisionResponse;
import com.example.complaints.masterdata.model.Subdivision;
import org.springframework.stereotype.Component;

@Component
public class SubdivisionMapper {

    public SubdivisionResponse toResponse(Subdivision s) {
        return new SubdivisionResponse(
                s.getId(),
                s.getCode(),
                s.getName(),
                s.getDistrict(),
                s.isActive(),
                DateUtils.toIst(s.getCreatedAt()),
                DateUtils.toIst(s.getUpdatedAt())
        );
    }
}

