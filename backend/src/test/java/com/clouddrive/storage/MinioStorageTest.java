package com.clouddrive.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.clouddrive.config.AppProperties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/** MinioStorage.headObjectSize：实测对象字节数（B5 依赖）。 */
class MinioStorageTest {

    @Test
    void headObjectSizeReturnsContentLength() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(123L).build());
        MinioStorage storage = new MinioStorage(client, new AppProperties());

        assertThat(storage.headObjectSize("users/1/a.txt")).isEqualTo(123L);
    }
}
