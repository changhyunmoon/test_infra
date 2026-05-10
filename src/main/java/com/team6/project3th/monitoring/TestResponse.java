package com.team6.project3th.monitoring;

import java.time.LocalDateTime;

public record TestResponse(
        Long id,
        String name,
        LocalDateTime createdAt
) {

    public static TestResponse from(TestEntity testEntity) {
        return new TestResponse(
                testEntity.getId(),
                testEntity.getName(),
                testEntity.getCreatedAt()
        );
    }
}
