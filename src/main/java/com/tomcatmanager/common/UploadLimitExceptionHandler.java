package com.tomcatmanager.common;

import com.tomcatmanager.release.ArtifactStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Multipart limits are enforced before any controller is selected, so this advice is global.
 * The browser checks the size first; this is the server-side backstop.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimitExceptionHandler {
    private final DataSize maxUploadSize;

    public UploadLimitExceptionHandler(@Value("${app.release.max-upload-size:1GB}") DataSize maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiExceptionHandler.ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ApiExceptionHandler.error(HttpStatus.CONTENT_TOO_LARGE,
                "The file is larger than the " + ArtifactStorage.humanSize(maxUploadSize.toBytes()) + " upload limit.", null);
    }
}
