package com.team6.project3th.monitoring;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TestService {

    private final TestRepository testRepository;

    public TestService(TestRepository testRepository) {
        this.testRepository = testRepository;
    }

    @Transactional
    public TestResponse create(String name) {
        TestEntity saved = testRepository.save(new TestEntity(name));
        return TestResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public long count() {
        return testRepository.count();
    }
}
