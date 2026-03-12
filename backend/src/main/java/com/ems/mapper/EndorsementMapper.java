package com.ems.mapper;

import com.ems.domain.model.EndorsementRequest;
import com.ems.dto.response.EndorsementResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EndorsementMapper {

    @Mapping(target = "existing", constant = "false")
    EndorsementResponse toResponse(EndorsementRequest endorsementRequest);
}
