package com.conversive.aep.mocks.leads;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LeadsController {

    private final LeadsService leads;

    public LeadsController(LeadsService leads) {
        this.leads = leads;
    }

    @GetMapping("/leads")
    public Map<String, Object> fetch(@RequestParam(value = "limit", required = false) Integer limit) {
        return leads.fetch(limit);
    }
}
