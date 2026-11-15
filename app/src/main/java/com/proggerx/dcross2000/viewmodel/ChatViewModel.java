package com.proggerx.dcross2000.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.ArrayList;

import com.proggerx.dcross2000.domain.ChatMessage;

public class ChatViewModel extends ViewModel {

    private final MutableLiveData<ArrayList<ChatMessage>> messageList = new MutableLiveData<>();

    public LiveData<ArrayList<ChatMessage>> getMessageList() {
        return messageList;
    }

    public void setMessageList(ArrayList<ChatMessage> newData) {
        messageList.setValue(newData);
    }

}