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

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationAction;
import com.cloudbees.hudson.plugins.folder.relocate.RelocationHandler;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.cli.CLICommand;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import jenkins.model.Jenkins;
import org.acegisecurity.AccessDeniedException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CLI command to move items to a folder
 */
@Extension
public class MoveCommand extends CLICommand {

    @Argument(metaVar = "FOLDER", usage = "Destination folder.", required = true)
    public String folder;
    @Argument(metaVar = "ITEMS", usage = "List of items to move.", index = 1, required = true)
    public List<TopLevelItem> items;
    @Option(name = "-create", aliases = "-c", usage = "If the destination folder does not exist, it is created.\n" +
            "This option is only available for Administrators")
    private boolean create;

    private ItemGroup destination = null;

    protected static final int SUCCESS_RETURN_CODE = 0;
    protected static final int ERROR_RETURN_CODE = 100;
    protected static final int VALIDATIONS_ERROR_CODE = 101;

    private static final Logger LOGGER = Logger.getLogger(MoveCommand.class.getName());

    @Override
    public String getShortDescription() {
        return "Moves items to a folder. Specify 'jenkins' to move to the root folder";
    }

    @Override
    protected int run() throws Exception {
        try {
            if (create) {
                try {
                    // This option is for Jenkins ADMIN only
                    Jenkins.get().checkPermission(Jenkins.ADMINISTER);
                } catch (AccessDeniedException | IllegalStateException e) {
                    failure("Only administrator users can use the '-create' option.");
                    return ERROR_RETURN_CODE;
                }
            }

            List<CLIOutput> validationsList = validateInput();
            if (!validationsList.isEmpty()) {
                failure("Error validating inputs", validationsList);
                return VALIDATIONS_ERROR_CODE;
            }

            validationsList = moveItems();

            success("Command finished", validationsList);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error calling Move CLI command", e);
            failure(e.getMessage());
            return ERROR_RETURN_CODE;
        }
        return SUCCESS_RETURN_CODE;
    }

    private List<CLIOutput> moveItems() {
        List<CLIOutput> output = new ArrayList<>();

        for(TopLevelItem item : items) {
            final String itemName = item.getFullName();
            if (destination == item.getParent()) {
                output.add(new CLIOutput(itemName, String.format("The item is already in the '%s' folder. Skipping", folder)));
            } else {
                List<RelocationHandler> chain = new ArrayList<RelocationHandler>();
                for (RelocationHandler handler : ExtensionList.lookup(RelocationHandler.class)) {
                    if (handler.applicability(item) != RelocationHandler.HandlingMode.SKIP) {
                        chain.add(handler);
                    }
                }
                if (chain.isEmpty()) {
                    output.add(new CLIOutput(itemName, "No known way to handle the item"));
                } else {
                    try {
                        AtomicReference<Item> newItem = new AtomicReference<Item>();
                        chain.get(0).handle(item, destination, newItem, chain.subList(1, chain.size()));
                        output.add(new CLIOutput(itemName, String.format("Successfully moved to '%s'. Check %s", folder, newItem.get().getUrl())));
                    } catch (IOException | InterruptedException e) {
                        output.add(new CLIOutput(itemName, String.format("Failed trying to move the item: %s", e.getMessage())));
                    }
                }
            }
        }

        return output;
    }

    private List<CLIOutput> validateInput() throws IOException {
        final Jenkins jenkins = Jenkins.get();

        List<CLIOutput> validations = new ArrayList<>();

        // Destination
        if (!"jenkins".equalsIgnoreCase(folder) && jenkins.getItemByFullName(folder) == null) {
            if (create) {
                createFolder(jenkins);
            } else {
                validations.add(new CLIOutput(folder, "Destination folder does not exist"));
            }
        }

        // Items to move
        for(TopLevelItem item : items) {
            // Check if the user has permission to move the item
            if(!item.hasPermission(RelocationAction.RELOCATE)) {
                validations.add(new CLIOutput(item.getFullName(), "You don't have permissions to move this element"));
            }

            // check if the destination is valid for the item
            ItemGroup dest = null;
            for (ItemGroup itemGroup : listDestinations(item)) {
                String destinationFolder = folder.equalsIgnoreCase("jenkins") ? "" : folder;
                // destination folder might begin with or without "/"
                if (("/" + itemGroup.getFullName()).equals(destinationFolder) || itemGroup.getFullName().equals(destinationFolder)) {
                    dest = itemGroup;
                    break;
                }
            }

            if (dest == null) {
                validations.add(new CLIOutput(item.getFullName(), String.format("%s is not a valid destination for this element", folder)));
            } else if (destination == null) {
                // It will be the same for all the items
                destination = dest;
            }
        }

        return validations;
    }

    private void createFolder(final Jenkins jenkins) throws IOException {
        Folder parent = null;
        String[] folders = folder.split("/");
        for(String f : folders) {
            String toCheck = parent == null ? f : parent.getFullName() + "/" + f;
            Item current = jenkins.getItemByFullName(toCheck);

            if (current == null) {
                if (parent == null) {
                    current = jenkins.createProject(Folder.class, f);
                } else {
                    current = parent.createProject(Folder.class, f);
                }
            } else if (!(current instanceof AbstractFolder)) {
                throw new IOException("Error trying to create the destination folder. '" + current.getFullName() + "' is not a folder. Aborting");
            }

            parent = (Folder) current;
        }
    }

    /**
     * List of destinations that the item can be moved to by the current user.
     */
    private Collection<ItemGroup<?>> listDestinations(Item item) {
        Collection<ItemGroup<?>> result = new LinkedHashSet<ItemGroup<?>>();
        for (RelocationHandler handler : ExtensionList.lookup(RelocationHandler.class)) {
            if (handler.applicability(item) == RelocationHandler.HandlingMode.HANDLE) {
                result.addAll(handler.validDestinations(item));
            }
        }
        return result;
    }

    private void success(@NonNull String message, List<CLIOutput> outputs) throws IOException {
        writeOutput(new CLIResult(message, CLIResult.Status.SUCCESS, outputs));
    }

    private void failure(@NonNull String message) throws IOException {
        failure(message, null);
    }

    private void failure(@NonNull String message, List<CLIOutput> outputs) throws IOException {
        writeOutput(new CLIResult(message, CLIResult.Status.FAILURE, outputs));
    }

    private void writeOutput(CLIResult res) {
        stdout.println(res.toString());
    }

}
