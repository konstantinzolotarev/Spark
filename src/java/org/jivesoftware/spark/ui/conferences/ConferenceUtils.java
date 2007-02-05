/**
 * $Revision: $
 * $Date: $
 *
 * Copyright (C) 2006 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Lesser Public License (LGPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.spark.ui.conferences;

import org.jivesoftware.resource.SparkRes;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.util.StringUtils;
import org.jivesoftware.smackx.Form;
import org.jivesoftware.smackx.FormField;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.jivesoftware.smackx.muc.MultiUserChat;
import org.jivesoftware.smackx.muc.RoomInfo;
import org.jivesoftware.smackx.packet.DataForm;
import org.jivesoftware.smackx.packet.DiscoverInfo;
import org.jivesoftware.spark.ChatManager;
import org.jivesoftware.spark.SparkManager;
import org.jivesoftware.spark.component.PasswordDialog;
import org.jivesoftware.spark.ui.ChatRoomNotFoundException;
import org.jivesoftware.spark.ui.rooms.GroupChatRoom;
import org.jivesoftware.spark.util.ModelUtil;
import org.jivesoftware.spark.util.SwingWorker;
import org.jivesoftware.spark.util.log.Log;
import org.jivesoftware.sparkimpl.settings.local.LocalPreferences;
import org.jivesoftware.sparkimpl.settings.local.SettingsManager;

import javax.swing.JOptionPane;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * ConferenceUtils allow for basic joining and inviting of users.
 */
public class ConferenceUtils {

    private ConferenceUtils() {
    }

    /**
     * Return a list of available Conference rooms from the server
     * based on the service name.
     *
     * @param serviceName the service name (ex. conference@jivesoftware.com)
     * @return a collection of rooms.
     * @throws Exception if an error occured during fetch.
     */
    public static Collection getRoomList(String serviceName) throws Exception {
        return MultiUserChat.getHostedRooms(SparkManager.getConnection(), serviceName);
    }

    /**
     * Return the number of occupants in a room.
     *
     * @param roomJID the full JID of the conference room. (ex. dev@conference.jivesoftware.com)
     * @return the number of occupants in the room if available.
     * @throws XMPPException thrown if an error occured during retrieval of the information.
     */
    public static int getNumberOfOccupants(String roomJID) throws XMPPException {
        final RoomInfo roomInfo = MultiUserChat.getRoomInfo(SparkManager.getConnection(), roomJID);
        return roomInfo.getOccupantsCount();
    }

    /**
     * Retrieve the date (in yyyyMMdd) format of the time the room was created.
     *
     * @param roomJID the jid of the room.
     * @return the formatted date.
     * @throws Exception throws an exception if we are unable to retrieve the date.
     */
    public static String getCreationDate(String roomJID) throws Exception {
        ServiceDiscoveryManager discoManager = ServiceDiscoveryManager.getInstanceFor(SparkManager.getConnection());

        final DateFormat dateFormatter = new SimpleDateFormat("yyyyMMdd'T'HH:mm:ss");
        final SimpleDateFormat simpleFormat = new SimpleDateFormat("EEE MM/dd/yyyy h:mm:ss a");
        DiscoverInfo infoResult = discoManager.discoverInfo(roomJID);
        DataForm dataForm = (DataForm)infoResult.getExtension("x", "jabber:x:data");
        if (dataForm == null) {
            return "Not available";
        }
        Iterator fieldIter = dataForm.getFields();
        String creationDate = "";
        while (fieldIter.hasNext()) {
            FormField field = (FormField)fieldIter.next();
            String label = field.getLabel();


            if (label != null && "Creation date".equalsIgnoreCase(label)) {
                Iterator valueIterator = field.getValues();
                while (valueIterator.hasNext()) {
                    Object oo = valueIterator.next();
                    creationDate = "" + oo;
                    Date date = dateFormatter.parse(creationDate);
                    creationDate = simpleFormat.format(date);
                }
            }
        }
        return creationDate;
    }


    /**
     * Joins a conference room using another thread. This allows for a smoother opening of rooms.
     *
     * @param roomName the name of the room.
     * @param roomJID  the jid of the room.
     * @param password the rooms password if required.
     */
    public static void joinConferenceOnSeperateThread(final String roomName, String roomJID, String password) {
        ChatManager chatManager = SparkManager.getChatManager();
        LocalPreferences pref = SettingsManager.getLocalPreferences();

        final MultiUserChat groupChat = new MultiUserChat(SparkManager.getConnection(), roomJID);
        final String nickname = pref.getNickname().trim();

        // Check if room already exists. If so, join room again.
        try {
            GroupChatRoom chatRoom = (GroupChatRoom)chatManager.getChatContainer().getChatRoom(roomJID);
            MultiUserChat muc = chatRoom.getMultiUserChat();
            if (!muc.isJoined()) {
                joinRoom(muc, nickname, password);
            }
            chatManager.getChatContainer().activateChatRoom(chatRoom);
            return;
        }
        catch (ChatRoomNotFoundException e) {
        }


        final GroupChatRoom room = new GroupChatRoom(groupChat);
        room.setTabTitle(roomName);


        if (isPasswordRequired(roomJID) && password == null) {
            final PasswordDialog passwordDialog = new PasswordDialog();
            password = passwordDialog.getPassword("Password Required", "This group chat room requires a password to enter.", SparkRes.getImageIcon(SparkRes.LOCK_16x16), SparkManager.getFocusedComponent());
            if (!ModelUtil.hasLength(password)) {
                return;
            }
        }


        final List<String> errors = new ArrayList<String>();
        final String userPassword = password;

        final SwingWorker startChat = new SwingWorker() {
            public Object construct() {
                if (!groupChat.isJoined()) {
                    int groupChatCounter = 0;
                    while (true) {
                        groupChatCounter++;
                        String joinName = nickname;
                        if (groupChatCounter > 1) {
                            joinName = joinName + groupChatCounter;
                        }
                        if (groupChatCounter < 10) {
                            try {
                                if (ModelUtil.hasLength(userPassword)) {
                                    groupChat.join(joinName, userPassword);
                                }
                                else {
                                    groupChat.join(joinName);
                                }
                                break;
                            }
                            catch (XMPPException ex) {
                                int code = 0;
                                if (ex.getXMPPError() != null) {
                                    code = ex.getXMPPError().getCode();
                                }

                                if (code == 0) {
                                    errors.add("No response from server.");
                                }
                                else if (code == 401) {
                                    errors.add("The password did not match the rooms password.");
                                }
                                else if (code == 403) {
                                    errors.add("You have been banned from this room.");
                                }
                                else if (code == 404) {
                                    errors.add("The room you are trying to enter does not exist.");
                                }
                                else if (code == 407) {
                                    errors.add("You are not a member of this room.\nThis room requires you to be a member to join.");
                                }
                                else if (code != 409) {
                                    break;
                                }
                            }
                        }
                        else {
                            break;
                        }
                    }
                }
                return "ok";
            }

            public void finished() {
                if (errors.size() > 0) {
                    String error = errors.get(0);
                    JOptionPane.showMessageDialog(SparkManager.getMainWindow(), error, "Unable to join the room at this time.", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                else if (groupChat.isJoined()) {
                    ChatManager chatManager = SparkManager.getChatManager();
                    chatManager.getChatContainer().addChatRoom(room);
                    chatManager.getChatContainer().activateChatRoom(room);
                }
                else {
                    JOptionPane.showMessageDialog(SparkManager.getMainWindow(), "Unable to join the room.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        };

        startChat.start();
    }

    /**
     * Presents the user with a dialog pre-filled with the room name and the jid.
     *
     * @param roomName the name of the room.
     * @param roomJID  the rooms jid.
     */
    public static void joinConferenceRoom(final String roomName, String roomJID) {
        JoinConferenceRoomDialog joinDialog = new JoinConferenceRoomDialog();
        joinDialog.joinRoom(roomJID, roomName);
    }


    /**
     * Joins a chat room without using the UI.
     *
     * @param groupChat the <code>MultiUserChat</code>
     * @param nickname  the nickname of the user.
     * @param password  the password to join the room with.
     * @return a List of errors, if any.
     */
    public static List joinRoom(MultiUserChat groupChat, String nickname, String password) {
        final List errors = new ArrayList();
        if (!groupChat.isJoined()) {
            int groupChatCounter = 0;
            while (true) {
                groupChatCounter++;
                String joinName = nickname;
                if (groupChatCounter > 1) {
                    joinName = joinName + groupChatCounter;
                }
                if (groupChatCounter < 10) {
                    try {
                        if (ModelUtil.hasLength(password)) {
                            groupChat.join(joinName, password);
                        }
                        else {
                            groupChat.join(joinName);
                        }
                        break;
                    }
                    catch (XMPPException ex) {
                        int code = 0;
                        if (ex.getXMPPError() != null) {
                            code = ex.getXMPPError().getCode();
                        }

                        if (code == 0) {
                            errors.add("No response from server.");
                        }
                        else if (code == 401) {
                            errors.add("A Password is required to enter this room.");
                        }
                        else if (code == 403) {
                            errors.add("You have been banned from this room.");
                        }
                        else if (code == 404) {
                            errors.add("The room you are trying to enter does not exist.");
                        }
                        else if (code == 407) {
                            errors.add("You are not a member of this room.\nThis room requires you to be a member to join.");
                        }
                        else if (code != 409) {
                            break;
                        }
                    }
                }
                else {
                    break;
                }
            }
        }

        return errors;
    }

    /**
     * Invites users to an existing room.
     *
     * @param serviceName the service name to use.
     * @param roomName    the name of the room.
     * @param jids        a collection of the users to invite.
     */
    public static final void inviteUsersToRoom(String serviceName, String roomName, Collection jids) {
        InvitationDialog inviteDialog = new InvitationDialog();
        inviteDialog.inviteUsersToRoom(serviceName, roomName, jids);
    }

    /**
     * Returns true if the room requires a password.
     *
     * @param roomJID the JID of the room.
     * @return true if the room requires a password.
     */
    public static final boolean isPasswordRequired(String roomJID) {
        // Check to see if the room is password protected
        ServiceDiscoveryManager discover = new ServiceDiscoveryManager(SparkManager.getConnection());


        try {
            DiscoverInfo info = discover.discoverInfo(roomJID);
            return info.containsFeature("muc_passwordprotected");
        }
        catch (XMPPException e) {
            Log.error(e);
        }
        return false;
    }

    /**
     * Creates a private conference.
     *
     * @param serviceName the service name to use for the private conference.
     * @param message     the message sent to each individual invitee.
     * @param roomName    the name of the room to create.
     * @param jids        a collection of the user JIDs to invite.
     */
    public static void createPrivateConference(String serviceName, String message, String roomName, Collection<String> jids) {
        final String roomJID = StringUtils.escapeNode(roomName) + "@" + serviceName;
        final MultiUserChat chatRoom = new MultiUserChat(SparkManager.getConnection(), roomJID);

        final GroupChatRoom room = new GroupChatRoom(chatRoom);

        try {
            LocalPreferences pref = SettingsManager.getLocalPreferences();
            chatRoom.create(pref.getNickname());

            // Since this is a private room, make the room not public and set user as owner of the room.
            Form submitForm = chatRoom.getConfigurationForm().createAnswerForm();
            submitForm.setAnswer("muc#roomconfig_publicroom", false);
            submitForm.setAnswer("muc#roomconfig_roomname", roomName);

            final List<String> owners = new ArrayList<String>();
            owners.add(SparkManager.getSessionManager().getBareAddress());
            submitForm.setAnswer("muc#roomconfig_roomowners", owners);

            chatRoom.sendConfigurationForm(submitForm);
        }
        catch (XMPPException e1) {
            Log.error("Unable to send conference room chat configuration form.", e1);
        }


        ChatManager chatManager = SparkManager.getChatManager();

        // Check if room already is open
        try {
            chatManager.getChatContainer().getChatRoom(room.getRoomname());
        }
        catch (ChatRoomNotFoundException e) {
            chatManager.getChatContainer().addChatRoom(room);
            chatManager.getChatContainer().activateChatRoom(room);
        }

        for (String jid : jids) {
            chatRoom.invite(jid, message);
            room.getTranscriptWindow().insertNotificationMessage("Waiting for " + jid + " to join.");
        }
    }

    /**
     * Enters a GroupChatRoom on the event thread.
     *
     * @param roomName the name of the room.
     * @param roomJID  the rooms jid.
     * @param password the rooms password (if any).
     * @return the GroupChatRoom created.
     */
    public static GroupChatRoom enterRoomOnSameThread(final String roomName, String roomJID, String password) {
        ChatManager chatManager = SparkManager.getChatManager();


        final LocalPreferences pref = SettingsManager.getLocalPreferences();

        final String nickname = pref.getNickname().trim();


        try {
            GroupChatRoom chatRoom = (GroupChatRoom)chatManager.getChatContainer().getChatRoom(roomJID);
            MultiUserChat muc = chatRoom.getMultiUserChat();
            if (!muc.isJoined()) {
                joinRoom(muc, nickname, password);
            }
            chatManager.getChatContainer().activateChatRoom(chatRoom);
            return chatRoom;
        }
        catch (ChatRoomNotFoundException e) {
        }

        final MultiUserChat groupChat = new MultiUserChat(SparkManager.getConnection(), roomJID);


        final GroupChatRoom room = new GroupChatRoom(groupChat);
        room.setTabTitle(roomName);


        if (isPasswordRequired(roomJID) && password == null) {
            password = JOptionPane.showInputDialog(null, "Enter Room Password", "Need Password", JOptionPane.QUESTION_MESSAGE);
            if (!ModelUtil.hasLength(password)) {
                return null;
            }
        }


        final List errors = new ArrayList();
        final String userPassword = password;


        if (!groupChat.isJoined()) {
            int groupChatCounter = 0;
            while (true) {
                groupChatCounter++;
                String joinName = nickname;
                if (groupChatCounter > 1) {
                    joinName = joinName + groupChatCounter;
                }
                if (groupChatCounter < 10) {
                    try {
                        if (ModelUtil.hasLength(userPassword)) {
                            groupChat.join(joinName, userPassword);
                        }
                        else {
                            groupChat.join(joinName);
                        }
                        break;
                    }
                    catch (XMPPException ex) {
                        int code = 0;
                        if (ex.getXMPPError() != null) {
                            code = ex.getXMPPError().getCode();
                        }

                        if (code == 0) {
                            errors.add("No response from server.");
                        }
                        else if (code == 401) {
                            errors.add("The password did not match the room's password.");
                        }
                        else if (code == 403) {
                            errors.add("You have been banned from this room.");
                        }
                        else if (code == 404) {
                            errors.add("The room you are trying to enter does not exist.");
                        }
                        else if (code == 407) {
                            errors.add("You are not a member of this room.\nThis room requires you to be a member to join.");
                        }
                        else if (code != 409) {
                            break;
                        }
                    }
                }
                else {
                    break;
                }
            }

        }


        if (errors.size() > 0) {
            String error = (String)errors.get(0);
            JOptionPane.showMessageDialog(SparkManager.getMainWindow(), error, "Unable to join the room at this time.", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        else if (groupChat.isJoined()) {
            chatManager.getChatContainer().addChatRoom(room);
            chatManager.getChatContainer().activateChatRoom(room);
        }
        else {
            JOptionPane.showMessageDialog(SparkManager.getMainWindow(), "Unable to join the room.", "Error", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        return room;
    }

    public static void enterRoom(final MultiUserChat groupChat, String tabTitle, final String nickname, final String password) {
        final GroupChatRoom room = new GroupChatRoom(groupChat);
        room.setTabTitle(tabTitle);
        if (room == null) {
            return;
        }


        final List errors = new ArrayList();


        if (!groupChat.isJoined()) {
            int groupChatCounter = 0;
            while (true) {
                groupChatCounter++;
                String joinName = nickname;
                if (groupChatCounter > 1) {
                    joinName = joinName + groupChatCounter;
                }
                if (groupChatCounter < 10) {
                    try {
                        if (ModelUtil.hasLength(password)) {
                            groupChat.join(joinName, password);
                        }
                        else {
                            groupChat.join(joinName);
                        }
                        break;
                    }
                    catch (XMPPException ex) {
                        int code = 0;
                        if (ex.getXMPPError() != null) {
                            code = ex.getXMPPError().getCode();
                        }

                        if (code == 0) {
                            errors.add("No response from server.");
                        }
                        else if (code == 401) {
                            errors.add("A Password is required to enter this room.");
                        }
                        else if (code == 403) {
                            errors.add("You have been banned from this room.");
                        }
                        else if (code == 404) {
                            errors.add("The room you are trying to enter does not exist.");
                        }
                        else if (code == 407) {
                            errors.add("You are not a member of this room.\nThis room requires you to be a member to join.");
                        }
                        else if (code != 409) {
                            break;
                        }
                    }
                }
                else {
                    break;
                }
            }
        }

        if (errors.size() > 0) {
            String error = (String)errors.get(0);
            JOptionPane.showMessageDialog(SparkManager.getMainWindow(), error, "Could Not Join Room", JOptionPane.ERROR_MESSAGE);
            return;
        }
        else if (groupChat.isJoined()) {
            ChatManager chatManager = SparkManager.getChatManager();
            chatManager.getChatContainer().addChatRoom(room);
            chatManager.getChatContainer().activateChatRoom(room);
        }
        else {
            JOptionPane.showMessageDialog(SparkManager.getMainWindow(), "Unable to join room.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

    }


}

