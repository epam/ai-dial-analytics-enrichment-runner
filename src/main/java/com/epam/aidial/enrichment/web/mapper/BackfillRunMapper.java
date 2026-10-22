package com.epam.aidial.enrichment.web.mapper;

import com.epam.aidial.enrichment.persistence.RunHistoryEntity;
import com.epam.aidial.enrichment.web.dto.backfill.BackfillRunDto;
import com.epam.aidial.enrichment.web.dto.backfill.BackfillRunListResponse;
import org.mapstruct.Mapper;

import java.util.List;

/** Maps run-history persistence entities to the backfill ops-API DTOs. */
@Mapper(componentModel = "spring")
public interface BackfillRunMapper {

    BackfillRunDto toDto(RunHistoryEntity entity);

    List<BackfillRunDto> toDtoList(List<RunHistoryEntity> runs);

    /** One representation for a row and a detail view: the listing carries exactly what the single read does. */
    default BackfillRunListResponse toListResponse(List<RunHistoryEntity> runs) {
        return new BackfillRunListResponse(toDtoList(runs));
    }
}
