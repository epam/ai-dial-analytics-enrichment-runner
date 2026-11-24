package com.epam.aidial.enrichment.exception;

import lombok.Getter;

/**
 * A dry run could not read its input from ADAS: ADAS was unreachable or answered a gateway error
 * ({@code adas_unavailable}), or it speaks another contract version ({@code adas_contract_mismatch}). Mapped to
 * HTTP 503 under that code.
 */
@Getter
public class DryRunAdasUnavailableException extends RuntimeException {

    public static final String UNAVAILABLE = "adas_unavailable";
    public static final String CONTRACT_MISMATCH = "adas_contract_mismatch";

    private final String code;

    public DryRunAdasUnavailableException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
