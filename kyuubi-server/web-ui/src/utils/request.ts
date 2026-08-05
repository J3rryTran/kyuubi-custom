/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import axios, { AxiosResponse } from 'axios'
import { useAuthStore } from '@/pinia/auth/auth'

// create an axios instance
const service = axios.create({
  baseURL: '/' // url = base url + request url
  // withCredentials: true, // send cookies when cross-domain requests
})

// request interceptor
service.interceptors.request.use(
  async (config) => {
    // do something before request is sent
    const authStore = useAuthStore()
    if (authStore.isAuthenticated) {
      // Renew a bearer token that is expired or close to it, so the request below
      // is not spent just to discover the token went stale.
      await authStore.refreshIfNeeded()
      if (authStore.isAuthenticated) {
        config.headers.Authorization = authStore.authToken
      }
    }
    return config
  },
  (error) => {
    // do something with request error
    return Promise.reject(error)
  }
)

// response interceptor
service.interceptors.response.use(
  /**
   * Determine the request status by custom code
   * Here is just an example
   * You can also judge the status by HTTP Status Code
   */
  (response: AxiosResponse) => {
    if (response.data) {
      switch (response.data.code) {
        case 503:
          // do something when code is 503
          break
      }
    }
    return response.data
  },
  async (error) => {
    // for debug
    // do something when error
    const status = error.response?.status
    // 403 is what the server returns when a bearer token is rejected, e.g. expired.
    if (status === 401 || status === 403) {
      const authStore = useAuthStore()
      const config = error.config
      // Give a stale bearer token exactly one chance to be renewed silently.
      if (
        config &&
        !config.__authRetried &&
        (await authStore.refreshIfNeeded(true))
      ) {
        config.__authRetried = true
        return service(config)
      }
      window.dispatchEvent(new CustomEvent('auth-required'))
    }
    return Promise.reject(error)
  }
)

export default service
