package com.example.complaints.masterdata.mapper;

import com.example.complaints.common.util.DateUtils;
import com.example.complaints.masterdata.dto.DistributionCenterResponse;
import com.example.complaints.masterdata.model.DistributionCenter;
import org.springframework.stereotype.Component;

@Component
public class DistributionCenterMapper {

    public DistributionCenterResponse toResponse(DistributionCenter dc) {
        return new DistributionCenterResponse(
                dc.getId(),
                dc.getSubdivisionId(),
                dc.getCode(),
                dc.getName(),
                dc.getAddress(),
                dc.isActive(),
                DateUtils.toIst(dc.getCreatedAt()),
                DateUtils.toIst(dc.getUpdatedAt())
        );
    }
}

