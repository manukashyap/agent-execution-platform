package com.conversive.aep.mocks.messaging;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessagingController {

    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) {
        this.messaging = messaging;
    }

    @PostMapping("/messaging/send")
    public Map<String, Object> send(@RequestBody MessagingService.SendRequest request) {
        return messaging.send(request);
    }
}
