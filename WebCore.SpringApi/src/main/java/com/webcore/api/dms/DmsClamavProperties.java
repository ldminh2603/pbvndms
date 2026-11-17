package com.webcore.api.dms;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Connection details for the clamd daemon that scans DMS uploads. */
@ConfigurationProperties("webcore.dms.clamav")
public record DmsClamavProperties(String host, int port, int connectTimeoutMs, int readTimeoutMs) { }
