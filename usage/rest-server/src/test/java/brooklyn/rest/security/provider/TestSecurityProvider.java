/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package brooklyn.rest.security.provider;

import javax.servlet.http.HttpSession;

import org.apache.http.auth.UsernamePasswordCredentials;

public class TestSecurityProvider implements SecurityProvider {

    public static final String USER = "test";
    public static final String PASSWORD = "opensesame";
    public static final UsernamePasswordCredentials CREDENTIAL =
            new UsernamePasswordCredentials(TestSecurityProvider.USER, TestSecurityProvider.PASSWORD);

    @Override
    public boolean isAuthenticated(HttpSession session) {
        return false;
    }

    @Override
    public boolean authenticate(HttpSession session, String user, String password) {
        return USER.equals(user) && PASSWORD.equals(password);
    }

    @Override
    public boolean logout(HttpSession session) {
        return false;
    }
}