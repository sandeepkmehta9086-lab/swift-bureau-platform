package com.swiftbureau.web;

import com.swiftbureau.security.SecurityUtil;
import com.swiftbureau.template.MessageTemplate;
import com.swiftbureau.template.TemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {

    private final TemplateService templateService;

    public TemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public List<MessageTemplate> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return templateService.list(SecurityUtil.current(), includeInactive);
    }

    @GetMapping("/{id}")
    public MessageTemplate get(@PathVariable UUID id) {
        return templateService.get(SecurityUtil.current(), id);
    }

    @PostMapping
    public MessageTemplate create(@RequestBody TemplateService.TemplateRequest request) {
        return templateService.create(SecurityUtil.current(), request);
    }

    @PatchMapping("/{id}")
    public MessageTemplate update(@PathVariable UUID id, @RequestBody TemplateService.TemplateRequest request) {
        return templateService.update(SecurityUtil.current(), id, request);
    }
}
