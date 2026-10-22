package com.epam.aidial.enrichment.persistence;

import java.io.Serializable;

/** Composite key of {@link GroupStateEntity}: a group key is only unique within its pipeline. */
public record GroupStateId(String pipelineName, String groupKey) implements Serializable {

    /** JPA requires a no-arg constructor on an {@code @IdClass}. */
    public GroupStateId() {
        this(null, null);
    }
}
