package com.epam.aidial.enrichment.web.mapper;

import com.epam.aidial.enrichment.group.GroupPage;
import com.epam.aidial.enrichment.persistence.GroupStateEntity;
import com.epam.aidial.enrichment.web.dto.admin.GroupStateDto;
import com.epam.aidial.enrichment.web.dto.admin.GroupStateResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps group-state entities to the ops introspection DTOs. */
@Mapper(componentModel = "spring")
public interface GroupStateMapper {

    @Mapping(target = "dirty", expression = "java(entity.isDirty())")
    GroupStateDto toDto(GroupStateEntity entity);

    default GroupStateResponse toResponse(GroupPage page) {
        return new GroupStateResponse(page.groups().stream().map(this::toDto).toList(), page.nextCursor(),
                page.hasMore(), page.total());
    }
}
