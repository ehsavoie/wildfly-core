/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.jboss.as.controller;

import org.jboss.as.controller.transform.TransformerOperationAttachment;

/**
 *
 * @author ehsavoie
 */
public interface ContextAttachmentsSupport {

     TransformerOperationAttachment getTransformerOperationAttachment(TransformerOperationAttachment attachment);

    /**
     * Retrieves an object that has been attached to this context.
     *
     * @param key the key to the attachment.
     * @param <T> the value type of the attachment.
     *
     * @return the attachment if found otherwise {@code null}.
     */
    <T> T getAttachment(OperationContext.AttachmentKey<T> key);

    /**
     * Attaches an arbitrary object to this context.
     *
     * @param key   they attachment key used to ensure uniqueness and used for retrieval of the value.
     * @param value the value to store.
     * @param <T>   the value type of the attachment.
     *
     * @return the previous value associated with the key or {@code null} if there was no previous value.
     */
    <T> T attach(OperationContext.AttachmentKey<T> key, T value);

    /**
     * Attaches an arbitrary object to this context only if the object was not already attached. If a value has already
     * been attached with the key provided, the current value associated with the key is returned.
     *
     * @param key   they attachment key used to ensure uniqueness and used for retrieval of the value.
     * @param value the value to store.
     * @param <T>   the value type of the attachment.
     *
     * @return the previous value associated with the key or {@code null} if there was no previous value.
     */
    <T> T attachIfAbsent(OperationContext.AttachmentKey<T> key, T value);

    /**
     * Detaches or removes the value from this context.
     *
     * @param key the key to the attachment.
     * @param <T> the value type of the attachment.
     *
     * @return the attachment if found otherwise {@code null}.
     */
    <T> T detach(OperationContext.AttachmentKey<T> key);
}
