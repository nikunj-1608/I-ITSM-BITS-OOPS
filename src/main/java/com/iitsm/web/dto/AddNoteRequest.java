package com.iitsm.web.dto;

import jakarta.validation.constraints.NotBlank;

// OOP (Immutability): AddNoteRequest is a record, so it is a read-only data carrier with no setters.
public record AddNoteRequest(
        @NotBlank(message = "Note text cannot be blank")
        String noteText,

        boolean internalOnly) {}

