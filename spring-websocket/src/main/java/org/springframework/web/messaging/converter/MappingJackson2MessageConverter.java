/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.messaging.converter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;

import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.Assert;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Implementation of {@link MessageConverter} that can read and write JSON using <a
 * href="http://jackson.codehaus.org/">Jackson 2.x's</a> {@link ObjectMapper}.
 * <p>
 * This converter can be used to bind to typed beans, or untyped {@link java.util.HashMap
 * HashMap} instances.
 * <p>
 * By default, this converter supports {@code application/json}. This can be overridden by
 * setting the {@link #setSupportedMediaTypes supportedMediaTypes} property.
 * <p>
 * Tested against Jackson 2.2; compatible with Jackson 2.0 and higher.
 *
 * @author Rossen Stoyanchev
 * @author Arjen Poutsma
 * @since 4.0
 */
public class MappingJackson2MessageConverter extends AbstractMessageConverter<Object> {

	private ObjectMapper objectMapper = new ObjectMapper();

	private boolean prefixJson = false;

	private Boolean prettyPrint;


	/**
	 * Construct a new {@code MappingJackson2HttpMessageConverter}.
	 */
	public MappingJackson2MessageConverter() {
		super(new MediaType("application", "json"),	new MediaType("application", "*+json"));
	}


	/**
	 * Set the {@code ObjectMapper} for this view. If not set, a default
	 * {@link ObjectMapper#ObjectMapper() ObjectMapper} is used.
	 * <p>
	 * Setting a custom-configured {@code ObjectMapper} is one way to take further control
	 * of the JSON serialization process. For example, an extended
	 * {@link org.codehaus.jackson.map.SerializerFactory} can be configured that provides
	 * custom serializers for specific types. The other option for refining the
	 * serialization process is to use Jackson's provided annotations on the types to be
	 * serialized, in which case a custom-configured ObjectMapper is unnecessary.
	 */
	public void setObjectMapper(ObjectMapper objectMapper) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		this.objectMapper = objectMapper;
		configurePrettyPrint();
	}

	/**
	 * Return the underlying {@code ObjectMapper} for this view.
	 */
	public ObjectMapper getObjectMapper() {
		return this.objectMapper;
	}

	/**
	 * Indicate whether the JSON output by this view should be prefixed with "{} &&".
	 * Default is false.
	 * <p>
	 * Prefixing the JSON string in this manner is used to help prevent JSON Hijacking.
	 * The prefix renders the string syntactically invalid as a script so that it cannot
	 * be hijacked. This prefix does not affect the evaluation of JSON, but if JSON
	 * validation is performed on the string, the prefix would need to be ignored.
	 */
	public void setPrefixJson(boolean prefixJson) {
		this.prefixJson = prefixJson;
	}

	/**
	 * Whether to use the {@link DefaultPrettyPrinter} when writing JSON.
	 * This is a shortcut for setting up an {@code ObjectMapper} as follows:
	 * <pre class="code">
	 * ObjectMapper mapper = new ObjectMapper();
	 * mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
	 * converter.setObjectMapper(mapper);
	 * </pre>
	 */
	public void setPrettyPrint(boolean prettyPrint) {
		this.prettyPrint = prettyPrint;
		configurePrettyPrint();
	}

	private void configurePrettyPrint() {
		if (this.prettyPrint != null) {
			this.objectMapper.configure(SerializationFeature.INDENT_OUTPUT, this.prettyPrint);
		}
	}


	@Override
	public boolean canConvertFromPayload(Class<?> clazz, MediaType mediaType) {
		JavaType javaType = getJavaType(clazz, null);
		return (this.objectMapper.canDeserialize(javaType) && canConvertFrom(mediaType));
	}

	@Override
	public boolean canConvertToPayload(Class<?> clazz, MediaType mediaType) {
		return (this.objectMapper.canSerialize(clazz) && canConvertTo(mediaType));
	}

	@Override
	protected boolean supports(Class<?> clazz) {
		// should not be called, since we override canRead/Write instead
		throw new UnsupportedOperationException();
	}

	@Override
	protected Object convertFromPayloadInternal(Class<? extends Object> clazz,
			MediaType contentType, byte[] payload) throws IOException {

		JavaType javaType = getJavaType(clazz, null);
		return readJavaType(javaType, payload);
	}

	private Object readJavaType(JavaType javaType, byte[] payload) {
		try {
			return this.objectMapper.readValue(payload, javaType);
		}
		catch (IOException ex) {
			throw new HttpMessageNotReadableException("Could not read JSON: " + ex.getMessage(), ex);
		}
	}

	@Override
	protected byte[] convertToPayloadInternal(Object object, MediaType contentType) throws IOException {

		ByteArrayOutputStream out = new ByteArrayOutputStream();

		// The following has been deprecated as late as Jackson 2.2 (April 2013);
		// preserved for the time being, for Jackson 2.0/2.1 compatibility.
		@SuppressWarnings("deprecation")
		JsonGenerator jsonGenerator =
				this.objectMapper.getJsonFactory().createJsonGenerator(out, JsonEncoding.UTF8);

		// A workaround for JsonGenerators not applying serialization features
		// https://github.com/FasterXML/jackson-databind/issues/12
		if (this.objectMapper.isEnabled(SerializationFeature.INDENT_OUTPUT)) {
			jsonGenerator.useDefaultPrettyPrinter();
		}

		try {
			if (this.prefixJson) {
				jsonGenerator.writeRaw("{} && ");
			}
			this.objectMapper.writeValue(jsonGenerator, object);
		}
		catch (JsonProcessingException ex) {
			// TODO: more specific exception
			throw new IllegalStateException("Could not write JSON: " + ex.getMessage(), ex);
		}

		return out.toByteArray();
	}

	/**
	 * Return the Jackson {@link JavaType} for the specified type and context class.
	 * <p>The default implementation returns {@code typeFactory.constructType(type, contextClass)},
	 * but this can be overridden in subclasses, to allow for custom generic collection handling.
	 * For instance:
	 * <pre class="code">
	 * protected JavaType getJavaType(Type type) {
	 *   if (type instanceof Class && List.class.isAssignableFrom((Class)type)) {
	 *     return TypeFactory.collectionType(ArrayList.class, MyBean.class);
	 *   } else {
	 *     return super.getJavaType(type);
	 *   }
	 * }
	 * </pre>
	 * @param type the type to return the java type for
	 * @param contextClass a context class for the target type, for example a class
	 * in which the target type appears in a method signature, can be {@code null}
	 * signature, can be {@code null}
	 * @return the java type
	 */
	protected JavaType getJavaType(Type type, Class<?> contextClass) {
		return this.objectMapper.getTypeFactory().constructType(type, contextClass);
	}

}