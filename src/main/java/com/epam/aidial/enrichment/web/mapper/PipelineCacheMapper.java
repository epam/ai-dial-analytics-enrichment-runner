package com.epam.aidial.enrichment.web.mapper;

import com.epam.aidial.enrichment.adas.dto.PipelineDto;
import com.epam.aidial.enrichment.web.dto.admin.PipelineCacheEntryDto;
import com.epam.aidial.enrichment.web.dto.admin.PipelineCacheResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Collection;

/** Maps cached pipelines to the ops introspection DTOs. */
@Mapper(componentModel = "spring")
public interface PipelineCacheMapper {

    @Mapping(target = "triggerKind", source = "trigger.kind")
    PipelineCacheEntryDto toEntry(PipelineDto pipeline);

    default PipelineCacheResponse toResponse(Collection<PipelineDto> pipelines) {
        return new PipelineCacheResponse(pipelines.stream().map(this::toEntry).toList());
    }
}
