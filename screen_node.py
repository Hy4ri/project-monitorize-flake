#!/usr/bin/env python3
import dbus
import os
import subprocess
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

DBusGMainLoop(set_as_default=True)
loop = GLib.MainLoop()
bus = dbus.SessionBus()

desktop = bus.get_object('org.freedesktop.portal.Desktop', '/org/freedesktop/portal/desktop')
sc = dbus.Interface(desktop, 'org.freedesktop.portal.ScreenCast')

state = {'step': 'create_session', 'session': None}

def launch_gstreamer(fd, node_id):
    # Pipeline: DMABuf → vapostproc (AMD GPU decode) → videoconvert → gtk/gl sink
    # Uses vah264dec-style path since vapostproc crashed with loop bug
    # Instead: force CPU copy via videorate then videoconvert
    pipelines = [
        # Option 1: DMABuf with videoscale forcing a CPU copy first
        f"gst-launch-1.0 pipewiresrc fd={fd} path={node_id} do-timestamp=true ! queue max-size-buffers=2 ! videoconvert n-threads=4 ! video/x-raw,format=BGRx ! videoconvert ! autovideosink sync=false",
        # Option 2: No format hint, just queue + videoconvert
        f"gst-launch-1.0 pipewiresrc fd={fd} path={node_id} do-timestamp=true ! queue ! videoconvert n-threads=4 ! ximagesink sync=false",
        # Option 3: glsinkbin with compositor
        f"gst-launch-1.0 pipewiresrc fd={fd} path={node_id} do-timestamp=true ! queue ! glupload ! glcolorconvert ! gldownload ! videoconvert ! autovideosink sync=false",
    ]

    for i, cmd in enumerate(pipelines):
        print(f"\n[Trying pipeline {i+1}]: {cmd}\n")
        ret = os.system(cmd)
        if ret == 0:
            print(f"Pipeline {i+1} succeeded.")
            break
        else:
            print(f"Pipeline {i+1} failed (exit {ret}), trying next...")

def on_response(response, results, **kw):
    step = state['step']

    if step == 'create_session':
        state['session'] = str(results['session_handle'])
        state['step'] = 'select_sources'
        sc.SelectSources(state['session'], {
            'types': dbus.UInt32(1),
            'multiple': False,
            'handle_token': dbus.String('tok2')
        })

    elif step == 'select_sources':
        state['step'] = 'start'
        sc.Start(state['session'], '', {'handle_token': dbus.String('tok3')})

    elif step == 'start':
        node_id = int(results['streams'][0][0])
        fd_obj = sc.OpenPipeWireRemote(state['session'], {})
        fd = fd_obj.take()
        print(f"Got NODE: {node_id}  FD: {fd}")
        # IMPORTANT: loop.quit() BEFORE os.system so we stop the GLib loop
        # but session object stays alive since 'sc' and 'state' are still in scope
        loop.quit()
        launch_gstreamer(fd, node_id)

bus.add_signal_receiver(on_response, signal_name='Response',
    dbus_interface='org.freedesktop.portal.Request')

sc.CreateSession({'handle_token': dbus.String('tok1'), 'session_handle_token': dbus.String('ses1')})
loop.run()
