package com.ems.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
public class SubmissionRouter {

    @Async
    public void routeAsync(UUID endorsementRequestId) {
        log.info("Routing endorsement {} for submission", endorsementRequestId);
    }
}
