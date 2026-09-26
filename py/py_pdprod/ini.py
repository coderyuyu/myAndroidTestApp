import os
import json
# import shutil

def newprojectfile(dst):
    template = new169()
    with open(dst, 'w') as _json_file:
        print('{file} saved!'.format(file=dst)) #
        json.dump(template, _json_file)
             
 	   	# _pdproj = proj_template() 
        # with open(outfile, 'w') as _json_file:
	   	#     json.dump(_pdproj, _json_file)
	   	#     print('{file} saved!'.format(file=outfile)) #
	   	# return open_project()   




def osname():
     return os.name

def MEDIA_PATH():
    if osname() == "nt":
        return "C:\\works\\android\\camera"
    else:
        return '/storage/emulated/0/DCIM/camera'


def PDPROJ_PATH():
    if osname() == "nt":
        return "C:\\works\\android\\PowerDirector\\projects"
    else:    
        return '/storage/emulated/0/PowerDirector/projects'
    
def PDPROJ_PATH2():
	return '/storage/emulated/0/Android/data/com.cyberlink.powerdirector.  DRA140225_01/files/projects'
def proj_template_2():
    return {
    "version": 20180703,
    "tracks": [
        {
            "type": 1,
            "valid": "true",
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": [
                {
                    "beginUs": 0,
                    "endUs": 0,
                    "valid": "false",
                    "volume": 1.0,
                    "isMute": "false",
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                },
                {
                    "beginUs": 0,
                    "endUs": 2000000,
                    "valid": "true",
                    "timelineClip": {
                        "shadowEnabled": "false",
                        "shadowFilled": "false",
                        "text": "標題",
                        "category": "Title",
                        "effect": "Default",
                        "coordinates": {
                            "x": 0.50047034,
                            "y": 0.50167227,
                            "rotateAngle": 0,
                            "width": 0.17873941,
                            "height": 0.21070234,
                            "horizontalAlign": 2,
                            "verticalAlign": 2
                        },
                        "fontPath": "__DEFAULT__",
                        "fontName": "預設",
                        "fontColor": -1,
                        "glfx": {
                            "name": "Default",
                            "category": "Title"
                        },
                        "fontStyle": 0,
                        "textAlignment": 0,
                        "normFontSize": 0.088428974,
                        "borderWidth": 0.0,
                        "borderColor": -1,
                        "shadowColor": -16777216,
                        "shadowDistance": 12,
                        "opacity": 1.0,
                        "faceOpacity": 1.0,
                        "borderOpacity": 1.0,
                        "shadowOpacity": 1.0,
                        "borderEnabled": "true",
                        "faceEnabled": "true",
                        "inTimeUs": 0,
                        "outTimeUs": 2000000,
                        "originalDurationUs": -1,
                        "type": 2
                    },
                    "volume": 1.0,
                    "isMute": "false",
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                }
            ]
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": [
                {
                    "beginUs": 0,
                    "endUs": 0,
                    "valid": "false",
                    "volume": 1.0,
                    "isMute": "false",
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                },
                {
                    "beginUs": 0,
                    "endUs": 2000000,
                    "valid": "true",
                    "timelineClip": {
                        "shadowEnabled": "false",
                        "shadowFilled": "false",
                        "text": "字幕字幕字幕字幕字幕字幕字幕",
                        "category": "Title",
                        "effect": "Default",
                        "coordinates": {
                            "x": 0.5023518,
                            "y": 0.90301,
                            "rotateAngle": 0,
                            "width": 0.43650046,
                            "height": 0.073578596,
                            "horizontalAlign": 2,
                            "verticalAlign": 2
                        },
                        "fontPath": "__DEFAULT__",
                        "fontName": "預設",
                        "fontColor": -1,
                        "glfx": {
                            "name": "Default",
                            "category": "Title"
                        },
                        "fontStyle": 1,
                        "textAlignment": 0,
                        "normFontSize": 0.031044215,
                        "borderWidth": 0.09,
                        "borderColor": -16777216,
                        "shadowColor": -16777216,
                        "shadowDistance": 12,
                        "opacity": 1.0,
                        "faceOpacity": 1.0,
                        "borderOpacity": 1.0,
                        "shadowOpacity": 1.0,
                        "borderEnabled": "true",
                        "faceEnabled": "true",
                        "inTimeUs": 0,
                        "outTimeUs": 2000000,
                        "originalDurationUs": -1,
                        "type": 2
                    },
                    "volume": 1.0,
                    "isMute": "false",
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                }
            ]
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": "true",
            "audioGain": 0.5,
            "volume": 0.5,
            "timelineUnit": []
        },
        {
            "type": 4,
            "valid": "true",
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 4,
            "valid": "true",
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        }
    ],
    "projectName": "16-9",
    "countSave": 16,
    "aspectRatio": 0,
    "isFirstTimeAddParticle": "false",
    "waterMarkRightMargin": -1.0,
    "waterMarkBottomMargin": -1.0
}

def proj_template():
    return {           
    "version": 20180703,
    "tracks": [
        {
            "type": 1,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
       {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": [
                {
                    "beginUs": 0,
                    "endUs": 0,
                    "valid": False,
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                },
                {
                    "beginUs": 0,
                    "endUs": 4032609,
                    "valid": True,
                    "timelineClip": {
                        "shadowEnabled": False,
                        "shadowFilled": False,
                        "text": "標題",
                        "category": "Title",
                        "effect": "Default",
                        "coordinates": {
                            "x": 0.50047034,
                            "y": 0.50167227,
                            "rotateAngle": 0,
                            "width": 0.17873941,
                            "height": 0.21070234,
                            "horizontalAlign": 2,
                            "verticalAlign": 2
                        },
                        "fontPath": "__DEFAULT__",
                        "fontName": "預設",
                        "fontColor": -1,
                        "glfx": {
                            "name": "Default",
                            "category": "Title"
                        },
                        "fontStyle": 1,
                        "textAlignment": 0,
                        "normFontSize": 0.088428974,
                        "borderWidth": 0.045,
                        "borderColor": -16777216,
                        "shadowColor": -16777216,
                        "shadowDistance": 12,
                        "opacity": 1.0,
                        "faceOpacity": 1.0,
                        "borderOpacity": 1.0,
                        "shadowOpacity": 1.0,
                        "borderEnabled": True,
                        "faceEnabled": True,
                        "inTimeUs": 0,
                        "outTimeUs": 4032609,
                        "originalDurationUs": -1,
                        "type": 2
                    },
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                }
            ]
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": [
                {
                    "beginUs": 0,
                    "endUs": 0,
                    "valid": False,
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                },
                {
                    "beginUs": 0,
                    "endUs": 4043478,
                    "valid": True,
                    "timelineClip": {
                        "shadowEnabled": False,
                        "shadowFilled": False,
                        "text": "字幕字幕",
                        "category": "Title",
                        "effect": "Default",
                        "coordinates": {
                            "x": 0.49952963,
                            "y": 0.8795987,
                            "rotateAngle": 0,
                            "width": 0.18626529,
                            "height": 0.110367894,
                            "horizontalAlign": 2,
                            "verticalAlign": 2
                        },
                        "fontPath": "__DEFAULT__",
                        "fontName": "預設",
                        "fontColor": -1,
                        "glfx": {
                            "name": "Default",
                            "category": "Title"
                        },
                        "fontStyle": 1,
                        "textAlignment": 0,
                        "normFontSize": 0.046095956,
                        "borderWidth": 0.09,
                        "borderColor": -16777216,
                        "shadowColor": -16777216,
                        "shadowDistance": 0,
                        "opacity": 1.0,
                        "faceOpacity": 1.0,
                        "borderOpacity": 1.0,
                        "shadowOpacity": 0.3137255,
                        "borderEnabled": True,
                        "faceEnabled": True,
                        "inTimeUs": 0,
                        "outTimeUs": 4043478,
                        "originalDurationUs": -1,
                        "type": 2
                    },
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                }
            ]
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 2,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        },
        {
            "type": 4,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": [
                {
                    "beginUs": 0,
                    "endUs": 0,
                    "valid": False,
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                },
                {
                    "beginUs": 0,
                    "endUs": 181580000,
                    "valid": True,
                    "timelineClip": {
                        "filePath": "/storage/emulated/0/Coder/py_pdprod/sample.m4a",
                        "mime-type": "audio/mp4",
                        "inTimeUs": 0,
                        "outTimeUs": 181580000,
                        "originalDurationUs": 181580000,
                        "type": 4
                    },
                    "volume": 1.0,
                    "isMute": False,
                    "fadeInDurationUs": 0,
                    "fadeOutDurationUs": 0,
                    "UserRotate": 0
                }
            ]
        },
        {
            "type": 4,
            "valid": True,
            "audioGain": 1.0,
            "volume": 1.0,
            "timelineUnit": []
        }
    ],
    "projectName": "16-9",
    "countSave": "28",
    "aspectRatio": "0",
    "isFirstTimeAddParticle": False,
    "waterMarkRightMargin": "-1.0",
    "waterMarkBottomMargin": "-1.0"
    }

def video_template():
    return 			{
					"beginUs": 0,
					"endUs": 4767000,
					"valid": True,
					"timelineClip": {
						"isTrimmedAfterReverse": False,
						"isSplittedAfterReverse": False,
						"InTimeUSBeforeReverse": 0,
						"OutTimeUSBeforeReverse": 0,
						"isUltraHDVideo": False,
						"filePath": "/storage/emulated/0/DCIM/Camera/2020111920/20201119_153707___0_play____.mp4",
						"mime-type": "video/mp4",
						"audio-tx": {
							"glfx": {
								"name": "Fade",
								"category": "Transition"
							},
							"durationUs": 4000000
						},
						"fx": [],
						"orientation": 0,
						"width": 1920,
						"height": 1080,
						"isReverse": False,
						"inTimeUs": 0,
						"outTimeUs": 4767000,
						"originalDurationUs": 4767000,
						"type": 1
					},
					"volume": 1.0,
					"isMute": False,
					"fadeInDurationUs": 0,
					"fadeOutDurationUs": 0,
					"UserRotate": 0
				}        

def image_template():
    return         	{
					"beginUs": 0,
					"endUs": 0,
					"valid": True,
					"timelineClip": {
						"isTrimmedAfterReverse": False,
						"isSplittedAfterReverse": False,
						"InTimeUSBeforeReverse": 0,
						"OutTimeUSBeforeReverse": 0,
						"isUltraHDVideo": False,
						"filePath": "/storage/emulated/0/DCIM/Camera/2020111920/20201119_154405_120.968333333333_24.4683333333333_0_play____.jpg",
						"out-tx": {
							"glfx": {
								"name": "NoTransition",
								"category": "private_"
							},
							"durationUs": 100000
						},
						"audio-tx": {
							"glfx": {
								"name": "Fade",
								"category": "Transition"
							},
							"durationUs": 4000000
						},
						"fx": [],
						"orientation": 0,
						"width": 3840,
						"height": 2160,
						"isReverse": False,
						"inTimeUs": 0,
						"outTimeUs": 3000000,
						"originalDurationUs": -1,
						"type": 1
					},
					"volume": 1.0,
					"isMute": False,
					"fadeInDurationUs": 0,
					"fadeOutDurationUs": 0,
					"UserRotate": 0
				}


def new169():
    return {
        "version":20180703,
        "tracks":[{"type":1,"valid":"true","audioGain":1.0,"volume":1.0,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[{"beginUs":0,"endUs":0,"valid":"false","volume":1.0,"isMute":"false","fadeInDurationUs":0,"fadeOutDurationUs":0,"UserRotate":0},{"beginUs":0,"endUs":2000000,"valid":"true","timelineClip":{"shadowEnabled":"false","shadowFilled":"false","text":"標題","category":"Title","effect":"Default","coordinates":{"x":0.50047034,"y":0.50167227,"rotateAngle":0,"width":0.17873941,"height":0.21070234,"horizontalAlign":2,"verticalAlign":2},"fontPath":"__DEFAULT__","fontName":"預設","fontColor":-1,"glfx":{"name":"Default","category":"Title"},"fontStyle":0,"textAlignment":0,"normFontSize":0.088428974,"borderWidth":0.0,"borderColor":-1,"shadowColor":-16777216,"shadowDistance":12,"opacity":1.0,"faceOpacity":1.0,"borderOpacity":1.0,"shadowOpacity":1.0,"borderEnabled":"true","faceEnabled":"true","inTimeUs":0,"outTimeUs":2000000,"originalDurationUs":-1,"type":2},"volume":1.0,"isMute":"false","fadeInDurationUs":0,"fadeOutDurationUs":0,"UserRotate":0}]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[{"beginUs":0,"endUs":0,"valid":"false","volume":1.0,"isMute":"false","fadeInDurationUs":0,"fadeOutDurationUs":0,"UserRotate":0},{"beginUs":0,"endUs":2000000,"valid":"true","timelineClip":{"shadowEnabled":"false","shadowFilled":"false","text":"字幕字幕字幕字幕字幕字幕字幕","category":"Title","effect":"Default","coordinates":{"x":0.5023518,"y":0.90301,"rotateAngle":0,"width":0.43650046,"height":0.073578596,"horizontalAlign":2,"verticalAlign":2},"fontPath":"__DEFAULT__","fontName":"預設","fontColor":-1,"glfx":{"name":"Default","category":"Title"},"fontStyle":1,"textAlignment":0,"normFontSize":0.031044215,"borderWidth":0.09,"borderColor":-16777216,"shadowColor":-16777216,"shadowDistance":12,"opacity":1.0,"faceOpacity":1.0,"borderOpacity":1.0,"shadowOpacity":1.0,"borderEnabled":"true","faceEnabled":"true","inTimeUs":0,"outTimeUs":2000000,"originalDurationUs":-1,"type":2},"volume":1.0,"isMute":"false","fadeInDurationUs":0,"fadeOutDurationUs":0,"UserRotate":0}]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":2,"valid":"true","audioGain":0.5,"volume":0.5,"timelineUnit":[]},{"type":4,"valid":"true","audioGain":1.0,"volume":1.0,"timelineUnit":[]},{"type":4,"valid":"true","audioGain":1.0,"volume":1.0,"timelineUnit":[]}],"projectName":"new","countSave":16,"aspectRatio":0,"isFirstTimeAddParticle":"false","waterMarkRightMargin":-1.0,"waterMarkBottomMargin":-1.0}
    
