/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3

import okhttp3.internal.Util
import okhttp3.internal.http.HttpMethod
import okhttp3.internal.toImmutableMap
import java.net.URL

/**
 * An HTTP request. Instances of this class are immutable if their [body] is null or itself
 * immutable.
 */
class Request internal constructor(
  internal val url: HttpUrl,
  builder: Builder
) {
  internal val method: String = builder.method
  internal val headers: Headers = builder.headers.build()
  internal val body: RequestBody? = builder.body
  internal val tags: Map<Class<*>, Any> = builder.tags.toImmutableMap()

  @Volatile
  private var cacheControl: CacheControl? = null // Lazily initialized

  val isHttps: Boolean
    get() = url.isHttps

  fun url(): HttpUrl = url

  fun method(): String = method

  fun headers(): Headers = headers

  fun header(name: String): String? = headers[name]

  fun headers(name: String): List<String> = headers.values(name)

  fun body(): RequestBody? = body

  /**
   * Returns the tag attached with `Object.class` as a key, or null if no tag is attached with
   * that key.
   *
   * Prior to OkHttp 3.11, this method never returned null if no tag was attached. Instead it
   * returned either this request, or the request upon which this request was derived with
   * [newBuilder].
   */
  fun tag(): Any? = tag(Any::class.java)

  /**
   * Returns the tag attached with [type] as a key, or null if no tag is attached with that
   * key.
   */
  fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])

  fun newBuilder(): Builder = Builder(this)

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no `Cache-Control` header.
   */
  fun cacheControl(): CacheControl {
    return cacheControl ?: CacheControl.parse(headers).also {
      this.cacheControl = it
    }
  }

  override fun toString(): String = "Request{method=$method, url=$url, tags=$tags}"

  open class Builder {
    internal var url: HttpUrl? = null
    internal var method: String
    internal var headers: Headers.Builder
    internal var body: RequestBody? = null

    /** A mutable map of tags, or an immutable empty map if we don't have any. */
    internal var tags: MutableMap<Class<*>, Any> = mutableMapOf()

    constructor() {
      this.method = "GET"
      this.headers = Headers.Builder()
    }

    internal constructor(request: Request) {
      this.url = request.url
      this.method = request.method
      this.body = request.body
      this.tags = if (request.tags.isEmpty()) {
        mutableMapOf()
      } else {
        request.tags.toMutableMap()
      }
      this.headers = request.headers.newBuilder()
    }

    open fun url(url: HttpUrl): Builder = apply {
      this.url = url
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if [url] is not a valid HTTP or HTTPS URL. Avoid this
     *     exception by calling [HttpUrl.parse]; it returns null for invalid URLs.
     */
    open fun url(url: String): Builder {
      // Silently replace web socket URLs with HTTP URLs.
      val finalUrl: String = when {
        url.regionMatches(0, "ws:", 0, 3, ignoreCase = true) -> {
          "http:${url.substring(3)}"
        }
        url.regionMatches(0, "wss:", 0, 4, ignoreCase = true) -> {
          "https:${url.substring(4)}"
        }
        else -> url
      }

      return url(HttpUrl.get(finalUrl))
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of [url] is not `http` or `https`.
     */
    open fun url(url: URL) = url(HttpUrl.get(url.toString()))

    /**
     * Sets the header named [name] to [value]. If this request already has any headers
     * with that name, they are all replaced.
     */
    open fun header(name: String, value: String) = apply {
      headers[name] = value
    }

    /**
     * Adds a header with [name] and [value]. Prefer this method for multiply-valued
     * headers like "Cookie".
     *
     * Note that for some headers including `Content-Length` and `Content-Encoding`,
     * OkHttp may replace [value] with a header derived from the request body.
     */
    open fun addHeader(name: String, value: String) = apply {
      headers.add(name, value)
    }

    /** Removes all headers named [name] on this builder. */
    open fun removeHeader(name: String) = apply {
      headers.removeAll(name)
    }

    /** Removes all headers on this builder and adds [headers]. */
    open fun headers(headers: Headers) = apply {
      this.headers = headers.newBuilder()
    }

    /**
     * Sets this request's `Cache-Control` header, replacing any cache control headers already
     * present. If [cacheControl] doesn't define any directives, this clears this request's
     * cache-control headers.
     */
    open fun cacheControl(cacheControl: CacheControl): Builder {
      val value = cacheControl.toString()
      return when {
        value.isEmpty() -> removeHeader("Cache-Control")
        else -> header("Cache-Control", value)
      }
    }

    open fun get() = method("GET", null)

    open fun head() = method("HEAD", null)

    open fun post(body: RequestBody) = method("POST", body)

    @JvmOverloads
    open fun delete(body: RequestBody? = Util.EMPTY_REQUEST) = method("DELETE", body)

    open fun put(body: RequestBody) = method("PUT", body)

    open fun patch(body: RequestBody) = method("PATCH", body)

    open fun method(method: String, body: RequestBody?): Builder = apply {
      require(method.isNotEmpty()) {
        "method.isEmpty() == true"
      }
      if (body == null) {
        require(!HttpMethod.requiresRequestBody(method)) {
          "method $method must have a request body."
        }
      } else {
        require(HttpMethod.permitsRequestBody(method)) {
          "method $method must not have a request body."
        }
      }
      this.method = method
      this.body = body
    }

    /** Attaches [tag] to the request using `Object.class` as a key. */
    open fun tag(tag: Any?): Builder = tag(Any::class.java, tag)

    /**
     * Attaches [tag] to the request using [type] as a key. Tags can be read from a
     * request using [Request.tag]. Use null to remove any existing tag assigned for [type].
     *
     * Use this API to attach timing, debugging, or other application data to a request so that
     * you may read it in interceptors, event listeners, or callbacks.
     */
    open fun <T> tag(type: Class<in T>, tag: T?) = apply {
      if (tag == null) {
        tags.remove(type)
      } else {
        if (tags.isEmpty()) {
          tags = mutableMapOf()
        }
        tags[type] = type.cast(tag)!! // Force-unwrap due to lack of contracts on Class#cast()
      }
    }

    open fun build(): Request = Request(
        checkNotNull(url) { "url == null" },
        this
    )
  }
}