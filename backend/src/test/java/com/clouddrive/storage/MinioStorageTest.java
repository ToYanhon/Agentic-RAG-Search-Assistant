package com.clouddrive.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.clouddrive.config.AppProperties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/** MinioStorage 测试：headObjectSize（B5）+ deleteObjects 分页（D6）。 */
class MinioStorageTest {

    @Test
    void headObjectSizeReturnsContentLength() {
        S3Client client = mock(S3Client.class);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(123L).build());
        MinioStorage storage = new MinioStorage(client, new AppProperties());

        assertThat(storage.headObjectSize("users/1/a.txt")).isEqualTo(123L);
    }

    @Test
    void deleteObjectsPagesByThousand() {
        // D6：1500 个 key 应分 2 批（1000 + 500），单请求不超过 S3 上限
        S3Client client = mock(S3Client.class);
        when(client.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());
        MinioStorage storage = new MinioStorage(client, new AppProperties());

        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            keys.add("k" + i);
        }
        storage.deleteObjects(keys);

        ArgumentCaptor<DeleteObjectsRequest> cap = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(client, times(2)).deleteObjects(cap.capture());
        List<DeleteObjectsRequest> reqs = cap.getAllValues();
        assertThat(reqs.get(0).delete().objects()).hasSize(1000);
        assertThat(reqs.get(1).delete().objects()).hasSize(500);
    }
}
