/*
 * Copyright 2015-2017 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.catalog.models;

import org.opencb.commons.utils.StringUtils;
import org.opencb.opencga.core.common.TimeUtils;

import static org.opencb.opencga.catalog.models.Session.Type.USER;


/**
 * Created by jacobo on 11/09/14.
 */
public class Session {

    private String id;
    private String ip;
    private String date;
    private Type type;

    // The session created could be either generated by the system for inner purposes or by the user itself.
    public enum Type {
        USER,
        SYSTEM
    }

    public Session() {
    }

    public Session(String ip, int length) {
        this(StringUtils.randomString(length), ip, TimeUtils.getTime(), USER);
    }

    public Session(String id, String ip, String date, Type type) {
        this.id = id;
        this.ip = ip;
        this.date = date;
        this.type = type;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("Session{");
        sb.append("id='").append(id).append('\'');
        sb.append(", ip='").append(ip).append('\'');
        sb.append(", date='").append(date).append('\'');
        sb.append(", type='").append(type).append('\'');
        sb.append('}');
        return sb.toString();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String generateNewId(int length) {
        this.id = StringUtils.randomString(length);
        return id;
    }
}
