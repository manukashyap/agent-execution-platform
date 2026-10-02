package com.conversive.aep.mocks.crm;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/crm/contacts")
public class CrmController {

    private final CrmService crm;

    public CrmController(CrmService crm) {
        this.crm = crm;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CrmService.ContactRequest request) {
        return crm.create(request);
    }

    @GetMapping
    public Map<String, Object> find(@RequestParam("external_ref") String externalRef) {
        return crm.findByExternalRef(externalRef);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        return crm.delete(id);
    }
}
