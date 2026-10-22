package com.epam.aidial.enrichment.web.mapper;

import com.epam.aidial.enrichment.dlq.DlqPage;
import com.epam.aidial.enrichment.persistence.DlqItemEntity;
import com.epam.aidial.enrichment.web.dto.dlq.DlqItemDto;
import com.epam.aidial.enrichment.web.dto.dlq.DlqListResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Maps dead-letter persistence entities to the ops-API DTOs, keeping the DTO records free of any
 * persistence dependency. {@code requeueable} is derived from the presence of a stored payload.
 */
@Mapper(componentModel = "spring")
public interface DlqItemMapper {

    @Mapping(target = "requeueable", expression = "java(entity.getPayload() != null)")
    DlqItemDto toDto(DlqItemEntity entity);

    List<DlqItemDto> toDtoList(List<DlqItemEntity> items);

    default DlqListResponse toListResponse(DlqPage page) {
        return new DlqListResponse(toDtoList(page.items()), page.nextCursor(), page.hasMore(),
                page.counts().total(), page.counts().requeueable());
    }
}
