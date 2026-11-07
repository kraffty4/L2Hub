"""--hidden exercises the ordinary launcher entrypoint without showing any window."""
import unittest
from unittest.mock import MagicMock,patch
from l2hub import __main__ as main


class HiddenMainTest(unittest.TestCase):
    def test_existing_mutex_never_signals_show(self):
        with patch.object(main,'k32') as win,patch.object(main.ctypes,'get_last_error',return_value=183):
            self.assertIsNone(main.single_instance(hidden=True))
            win.CreateMutexW.assert_called_once()
            win.OpenEventW.assert_not_called()
            win.SetEvent.assert_not_called()
            win.CreateEventW.assert_not_called()

    def test_normal_existing_mutex_still_signals_show(self):
        with patch.object(main,'k32') as win,patch.object(main.ctypes,'get_last_error',return_value=183):
            self.assertIsNone(main.single_instance())
            win.OpenEventW.assert_called_once_with(0x0002,False,main.SHOW_EVENT)
            win.SetEvent.assert_called_once()

    def test_hidden_main_uses_loaded_and_no_geometry_or_tray(self):
        window=MagicMock()
        with patch.object(main.sys,'argv',['l2hub','--hidden','--debug']),patch.object(main,'single_instance',return_value=123) as singleton,\
             patch.object(main,'initial_geometry',side_effect=AssertionError('hidden requested desktop geometry')),\
             patch.object(main,'Api') as api,patch.object(main.webview,'create_window',return_value=window) as create,\
             patch.object(main.webview,'start',side_effect=lambda callback,**kw:callback()) as start,\
             patch.object(main.appsettings,'load',return_value={'theme':'gold','language':'ru'}),\
             patch.object(main.winframe,'fit_maximized') as fit,patch.object(main.winframe,'fix_styles') as styles:
            main.main()
        singleton.assert_called_once_with(hidden=True)
        self.assertTrue(create.call_args.kwargs['hidden'])
        window.events.loaded.wait.assert_called_once_with()
        window.events.shown.wait.assert_not_called()
        for name in ['show','restore','maximize','resize','destroy']:
            getattr(window,name).assert_not_called()
        fit.assert_not_called();styles.assert_not_called()
        api.return_value._save_window.assert_not_called()
        self.assertTrue(start.call_args.kwargs['private_mode'])
        self.assertFalse(start.call_args.kwargs['debug'])
        self.assertIn('webview-hidden',start.call_args.kwargs['storage_path'])

    def test_normal_main_keeps_normal_start_options(self):
        with patch.object(main.sys,'argv',['l2hub','--debug']),patch.object(main,'single_instance',return_value=123) as singleton,\
             patch.object(main,'initial_geometry',return_value={'width':1400,'height':900}) as geometry,\
             patch.object(main,'Api'),patch.object(main.webview,'create_window') as create,\
             patch.object(main.webview,'start') as start,patch.object(main.appsettings,'load',return_value={'theme':'gold','language':'ru'}):
            main.main()
        singleton.assert_called_once_with(hidden=False)
        geometry.assert_called_once()
        self.assertFalse(create.call_args.kwargs['hidden'])
        self.assertEqual(create.call_args.kwargs['width'],1400)
        self.assertFalse(start.call_args.kwargs['private_mode'])
        self.assertTrue(start.call_args.kwargs['debug'])
        self.assertNotIn('storage_path',start.call_args.kwargs)


if __name__=='__main__':unittest.main()
