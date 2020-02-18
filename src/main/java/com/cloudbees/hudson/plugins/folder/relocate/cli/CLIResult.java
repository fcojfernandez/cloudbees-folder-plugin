/*
 * The MIT License
 *
 * Copyright 2020 CloudBees.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.hudson.plugins.folder.relocate.cli;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * An output message linked to a specific component
 */
public class CLIResult {

    /**
     * General message.
     */
    private final String message;

    /**
     * Detailed output
     */
    private final List<CLIOutput> outputs = new ArrayList<>();

    /**
     * The status: "success" or "failure".
     */
    private final Status status;

    public CLIResult(@NonNull String message, @NonNull Status status) {
        this(message, status, null);
    }

    public CLIResult(@NonNull String message, @NonNull Status status, List<CLIOutput> outputs) {
        this.message = message;
        this.status = status;
        if (outputs != null) {
            this.outputs.addAll(outputs);
        }
    }

    public String getMessage() {
        return message;
    }

    public Status getStatus() {
        return status;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("");
        sb.append("[").append(status).append("]: ").append(message);
        for (CLIOutput output : outputs) {
            sb.append("\n\t> ").append(output.toString());
        }

        return sb.toString();
    }

    public enum Status {
        SUCCESS,
        FAILURE
    }
}
