package com.clouddrive.indexnotify;

/**
 * 索引通知发送端口，对应 Go indexnotify.Sender。返回是否成功。
 */
public interface Sender {

	boolean send(String kind, long fileId, long ownerId);

}